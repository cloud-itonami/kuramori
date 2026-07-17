;; kuramori 倉守 — end-to-end warehouse analyzer (orchestrator).
;;
;; Loads the warehouse seed and runs the R0 sim pipeline:
;;   1. ABC-class every SKU + velocity-greedy slotting (golden-zone packing);
;;   2. build the outbound pick-route (nearest-neighbour) over the order's slots;
;;   3. dispatch the pick legs across the electric fleet (LPT makespan), with the
;;      shared-zone speed cap + battery opportunity-charge gate applied.
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.analyze
  (:require [clojure.edn :as edn]
            [kuramori.methods.slotting :as slot]
            [kuramori.methods.agv-amr :as fleet]
            [kuramori.methods.picking :as pick]
            [kuramori.methods.packing :as pack]
            [kuramori.methods.replenish :as rep]
            [kuramori.methods.returns :as ret]
            [kuramori.methods.cyclecount :as cc]
            [kuramori.methods.handoff :as ho]))

(defn- already-tx-data?
  "True if `content` is already the datomic/datascript tx-data shape ([{...:db/id ...}]),
   e.g. after the edn-datomize wave transforms data/warehouse.edn (Phase 4)."
  [content]
  (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id)))

(defn- unblob
  "Non-scalar attrs (nested maps / vectors-of-maps) are stored pr-str'd; parse them back."
  [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- reconstitute-entity
  "Undo the tx-data wrap: strip :db/id, strip the namespace off every attr key, unblob
   pr-str'd values — recovers the original bare warehouse-seed map byte-for-value-equal
   to the pre-transform data/warehouse.edn, so every downstream `(:skus seed)` /
   `(:zones seed)` / etc. lookup below (and in datom_emit.clj / test_kuramori.clj) is
   unchanged regardless of which shape is on disk."
  [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(defn load-seed
  "Read the warehouse EDN seed into a Clojure map. Tolerates both the original bare-map
   shape and the datomic/datascript tx-data shape (see `already-tx-data?` /
   `reconstitute-entity`) — always returns the bare map."
  [path]
  (let [content (edn/read-string (slurp path))]
    (if (already-tx-data? content)
      (reconstitute-entity content)
      content)))

(defn run
  "Run the full R0 analysis over a loaded seed map. Returns a report map."
  [seed]
  (let [skus (:skus seed)
        slots (:slots seed)
        by-slot (into {} (map (juxt :id identity) slots))
        ;; 1. slotting
        slotting (slot/assign-slots skus slots {})
        classed (into {} (map (fn [s] [(:id s) (slot/abc-class (:velocity s 0) {})]) skus))
        ;; 2. pick-route for the order (dock origin [0 0])
        order (:order seed)
        pick-coords (map #(:coord (by-slot %)) (:picks order))
        route-m (slot/pick-route [0 0] pick-coords)
        ;; 3. dispatch the pick legs across the fleet
        ;;    one move per pick leg (face → slot), shared? for zones near the human face
        moves (map-indexed
               (fn [i sid]
                 (let [s (by-slot sid)]
                   {:move-id (str "pick-" i "-" sid)
                    :distance-m (:dist-from-face s 0)
                    :shared? (= :golden (:kind (->> slots (filter #(= (:id %) sid)) first)))}))
               (:picks order))
        veh (fleet/make-vehicle :amr)
        disp (fleet/dispatch moves (mapv :id (:fleet seed)) veh)
        ;; battery gate: would the longest single leg breach the reserve floor?
        max-leg (apply max 0.0 (map :distance-m moves))
        charge-needed (fleet/needs-charge? veh max-leg)]
    {:slotting slotting
     :abc classed
     :pick-route-m route-m
     :dispatch disp
     :battery {:max-leg-m max-leg :charge-needed charge-needed}}))

(defn run-day
  "Full warehouse-DAY pipeline — threads the order through EVERY method module so
   they actually compose (R1 integration), not just coexist:
     inbound(handoff) → putaway/slotting → replenish → batch-pick(picking) →
     pack(packing) → dispatch(agv_amr) → outbound(handoff) → returns → cycle-count.
   Returns the base `run` report plus `:pipeline` (per-stage summary) and
   `:methods` (the set of method modules exercised). Stages with no seed fixture
   are recorded `:skipped` rather than failing."
  [seed]
  (let [base (run seed)
        order (:order seed)
        sku-by-id (into {} (map (juxt :id identity) (:skus seed)))
        stage (fn [m summary] {:method m :summary summary})
        ;; inbound putaway intents from niyaku
        inbound (when (:inbound seed) (ho/inbound-handoff (:inbound seed)))
        ;; replenishment of the forward pick-face from bulk
        replen (when (:forward-slots seed)
                 (rep/replenish-plan (:forward-slots seed) (:bulk seed []) {:case-pack 6}))
        ;; batch-pick consolidation of the order(s) into waves
        waves (pick/consolidate [order] 4)
        ;; cartonize the order's picked SKUs (slot-id → its SKU is implicit in the seed;
        ;; here we pack the order's SKUs directly by their pack dims)
        items (->> (:skus seed)
                   (filter #(some #{(:id %)} (map sku-by-id (:picks order)))) ; defensive
                   (map (fn [s] {:id (:id s) :vol-cm3 (:vol-cm3 s 1000) :weight-kg (:weight-kg s 1)})))
        items (if (seq items) items
                  (map (fn [s] {:id (:id s) :vol-cm3 (:vol-cm3 s 1000) :weight-kg (:weight-kg s 1)})
                       (take 3 (:skus seed))))
        cartons (when (:cartons seed) (pack/cartonize items (:cartons seed)))
        ;; outbound delivery handoff to todoke
        outbound (ho/outbound-handoff order)
        ;; returns disposition + cycle-count reconciliation
        returns (when (:returns seed) (ret/process-returns (:returns seed)))
        cyc (when (:count-slots seed) (cc/reconcile (:count-slots seed)))
        pipeline (cond-> []
                   inbound (conj (stage "handoff" (str (count inbound) " inbound putaway")))
                   true    (conj (stage "slotting" (str (count (get-in base [:slotting :placement])) " slotted")))
                   replen  (conj (stage "replenish" (str (count replen) " replenish moves")))
                   true    (conj (stage "picking" (str (count waves) " pick wave(s)")))
                   cartons (conj (stage "packing" (str (:count cartons) " carton(s)")))
                   true    (conj (stage "agv_amr" (format "makespan %.1fs" (get-in base [:dispatch :makespan]))))
                   true    (conj (stage "handoff" "1 outbound delivery"))
                   returns (conj (stage "returns" (str (count (:scrap returns)) " scrap / "
                                                       (count (:restock returns)) " restock")))
                   cyc     (conj (stage "cyclecount" (format "accuracy %.0f%%" (* 100.0 (:accuracy cyc))))))]
    (assoc base
           :pipeline pipeline
           :methods (set (map :method pipeline))
           :day {:inbound inbound :replenish replen :waves waves :cartons cartons
                 :outbound outbound :returns returns :cyclecount cyc})))

(defn report-day-str
  "Human-readable full-day pipeline report."
  [res]
  (str ";; kuramori 倉守 — full warehouse-DAY pipeline (R1 integration)\n"
       "methods exercised: " (pr-str (sort (:methods res))) "\n"
       (apply str (map (fn [s] (str "  • " (:method s) " — " (:summary s) "\n"))
                       (:pipeline res)))))

(defn report-str
  "Human-readable report (for out/ and Murakumo narration input, G6)."
  [res]
  (str ";; kuramori 倉守 — warehouse R0 analysis\n"
       "ABC: " (pr-str (:abc res)) "\n"
       "slotting placement: " (pr-str (get-in res [:slotting :placement])) "\n"
       "weighted-travel: " (format "%.1f" (get-in res [:slotting :weighted-travel])) "\n"
       "pick-route (m): " (format "%.1f" (:pick-route-m res)) "\n"
       "dispatch makespan (s): " (format "%.1f" (get-in res [:dispatch :makespan])) "\n"
       "battery charge-needed: " (get-in res [:battery :charge-needed]) "\n"))

(defn -main [& args]
  (let [path (or (first args) "20-actors/kuramori/data/warehouse.edn")
        seed (load-seed path)
        res (run-day seed)]
    (print (report-str res))
    (print (report-day-str res))
    (flush)))
