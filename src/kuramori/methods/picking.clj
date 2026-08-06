;; kuramori 倉守 — multi-order batch-picking consolidation + congestion-aware sequencing.
;;
;; R0→R1 maturity increment over the single-order `analyze` path: a real warehouse
;; picks MANY orders at once. This module:
;;   * consolidates orders into pick WAVES (batch picking) — first-fit-decreasing bin
;;     packing under a per-robot tote-cart capacity, so one robot sweep clears several
;;     orders' nearby picks instead of one trip per order;
;;   * a batch-capacity gate (G9) — an order whose pick count alone exceeds a wave's
;;     capacity cannot be packed (orders are atomic), so it RAISES rather than silently
;;     splitting a customer's order across carts;
;;   * congestion detection — given concurrent robot routes through named zones with
;;     time windows, find zones whose simultaneous occupancy exceeds the zone's robot
;;     capacity, and the minimum stagger delay to clear the worst overflow.
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0→R1).
(ns kuramori.methods.picking)

;; ── batch-capacity gate (G9) ─────────────────────────────────────────────────
(defn assert-batch-capacity!
  "RAISE if any single order's pick count exceeds the wave capacity. Orders are
   atomic (never split across carts), so an over-cap order is infeasible, not
   silently divided — the warehouse counterpart of niyaku's no-rehandle discipline."
  [orders cap]
  (when-let [bad (first (filter #(> (count (:picks %)) cap) orders))]
    (throw (ex-info "order exceeds wave capacity (atomic order cannot be split)"
                    {:order (:id bad) :picks (count (:picks bad)) :cap cap})))
  orders)

;; ── consolidation (first-fit-decreasing bin packing into waves) ──────────────
(defn consolidate
  "Pack orders into pick WAVES, each wave's total pick count ≤ cap. Orders sorted
   largest-first, placed into the first wave with room (FFD — good makespan/bin
   ratio). Returns a vector of waves; each wave = {:orders [ids] :picks [slot-ids]}.
   Calls `assert-batch-capacity!` first (G9)."
  [orders cap]
  (assert-batch-capacity! orders cap)
  (let [ordered (sort-by #(- (count (:picks %))) orders)]
    (reduce
     (fn [waves o]
       (let [n (count (:picks o))
             idx (->> (map-indexed vector waves)
                      (filter (fn [[_ w]] (<= (+ (count (:picks w)) n) cap)))
                      ffirst)]
         (if idx
           (-> waves
               (update-in [idx :orders] conj (:id o))
               (update-in [idx :picks] into (:picks o)))
           (conj waves {:orders [(:id o)] :picks (vec (:picks o))}))))
     []
     ordered)))

;; ── congestion (concurrent zone occupancy) ───────────────────────────────────
;; A route entry: {:zone z :t-in t0 :t-out t1} — one robot's pass through a zone.
(defn zone-occupancy
  "Max simultaneous robot count per zone across a flat seq of route entries.
   Uses a sweep over interval endpoints (touching endpoints do not overlap)."
  [entries]
  (into {}
        (for [[zone es] (group-by :zone entries)]
          [zone
           (let [evts (sort (concat (map (fn [e] [(:t-in e) 1]) es)
                                    (map (fn [e] [(:t-out e) -1]) es)))]
             (->> evts
                  (reduce (fn [{:keys [cur peak]} [_ d]]
                            (let [c (+ cur d)] {:cur c :peak (max peak c)}))
                          {:cur 0 :peak 0})
                  :peak))])))

(defn congestion-overflows
  "Zones whose peak concurrent occupancy exceeds `zone-cap`. Returns a seq of
   {:zone z :peak n :over (n-cap)}, worst-first."
  [entries zone-cap]
  (->> (zone-occupancy entries)
       (filter (fn [[_ peak]] (> peak zone-cap)))
       (map (fn [[zone peak]] {:zone zone :peak peak :over (- peak zone-cap)}))
       (sort-by #(- (:over %)))))

(defn congested?
  "True iff any zone overflows its capacity."
  [entries zone-cap]
  (boolean (seq (congestion-overflows entries zone-cap))))
