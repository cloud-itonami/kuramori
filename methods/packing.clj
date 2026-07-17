;; kuramori 倉守 — cartonization (pack picked items into shipping cartons).
;;
;; Once an order is picked, the items must be packed into shipping cartons before the
;; outbound handoff to todoke. Cartonization chooses how few/how small a carton (or set
;; of cartons) holds the picked items under two hard bounds — VOLUME and WEIGHT. This
;; module:
;;   * `fits?` — a single item fits a single carton iff its volume AND weight both fit;
;;   * `cartonize` — given items + a list of carton TYPES, choose the SMALLEST single
;;     carton whose volume ≥ Σitem-vol AND max-kg ≥ Σitem-weight; when no single carton
;;     holds all of them, first-fit-decreasing (FFD) split the items across multiple
;;     cartons of the LARGEST carton type;
;;   * a no-phantom-pack gate — an item that exceeds the LARGEST carton on its own is
;;     UNPACKABLE and RAISES via ex-info rather than being silently dropped or wedged
;;     into a too-small box, matching the actor's raising-gate discipline (slotting G7 /
;;     picking G9 / handoff G10 / replenish).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.packing)

;; ── fits? ──────────────────────────────────────────────────────────────────────
;; item   = {:id :vol-cm3 :weight-kg}
;; carton = {:id :vol-cm3 :max-kg}
(defn fits?
  "True iff `item` fits `carton` on BOTH bounds: item volume ≤ carton volume AND
   item weight ≤ carton max-kg."
  [item carton]
  (and (<= (:vol-cm3 item) (:vol-cm3 carton))
       (<= (:weight-kg item) (:max-kg carton))))

(defn- carton-size
  "A scalar size key for ordering carton types smallest-first (volume primary,
   then weight-capacity)."
  [carton]
  [(:vol-cm3 carton) (:max-kg carton)])

(defn- group-fits?
  "True iff a group of items (summed volume + weight) fits one `carton`."
  [items carton]
  (and (<= (reduce + (map :vol-cm3 items)) (:vol-cm3 carton))
       (<= (reduce + (map :weight-kg items)) (:max-kg carton))))

(defn- pack-line
  "Build the per-carton readout: type id, item ids, and volume/weight utilisation
   in (0..1] against the chosen carton type."
  [carton items]
  (let [vol (reduce + (map :vol-cm3 items))
        wt  (reduce + (map :weight-kg items))]
    {:carton-type (:id carton)
     :items       (mapv :id items)
     :vol-util    (/ (double vol) (:vol-cm3 carton))
     :weight-util (/ (double wt) (:max-kg carton))}))

;; ── ffd-split ────────────────────────────────────────────────────────────────
(defn- ffd-split
  "First-fit-decreasing pack of `items` into cartons of one `carton` type.
   Items sorted largest-volume-first, each placed into the first open carton with
   room on BOTH bounds, else a fresh carton. Returns a vector of item-groups."
  [items carton]
  (let [ordered (sort-by #(- (:vol-cm3 %)) items)]
    (reduce
     (fn [bins it]
       (let [idx (->> (map-indexed vector bins)
                      (filter (fn [[_ grp]] (group-fits? (conj grp it) carton)))
                      ffirst)]
         (if idx
           (update bins idx conj it)
           (conj bins [it]))))
     []
     ordered)))

;; ── cartonize ────────────────────────────────────────────────────────────────
(defn cartonize
  "Pack `items` into shipping carton(s) chosen from `carton-types`.

   1. RAISE (ex-info) if ANY single item exceeds the LARGEST carton type on its own
      (unpackable — no phantom pack; the over-size item must surface, never be wedged
      into a too-small box or dropped).
   2. Choose the SMALLEST single carton whose volume ≥ Σitem-vol AND max-kg ≥ Σitem-weight;
      if one exists, pack everything into that one carton.
   3. Otherwise FFD-split the items across multiple cartons of the LARGEST carton type.

   Returns {:cartons [{:carton-type :items [ids] :vol-util :weight-util}] :count n}."
  [items carton-types]
  (when (empty? carton-types)
    (throw (ex-info "no carton types available" {:items (mapv :id items)})))
  (let [sorted  (sort-by carton-size carton-types)
        largest (last sorted)]
    ;; gate: every item must fit the largest carton on its own.
    (when-let [bad (first (remove #(fits? % largest) items))]
      (throw (ex-info "item exceeds the largest carton (unpackable — no phantom pack)"
                      {:item        (:id bad)
                       :vol-cm3     (:vol-cm3 bad)
                       :weight-kg   (:weight-kg bad)
                       :largest     (:id largest)
                       :largest-vol (:vol-cm3 largest)
                       :largest-kg  (:max-kg largest)})))
    (if (empty? items)
      {:cartons [] :count 0}
      (if-let [single (first (filter #(group-fits? items %) sorted))]
        ;; one smallest-fitting carton holds everything
        {:cartons [(pack-line single items)] :count 1}
        ;; no single carton fits all → FFD split into largest-type cartons
        (let [groups (ffd-split items largest)
              lines  (mapv #(pack-line largest %) groups)]
          {:cartons lines :count (count lines)})))))

;; ── demo ───────────────────────────────────────────────────────────────────────
(defn -main [& _]
  (let [carton-types [{:id "S" :vol-cm3 8000   :max-kg 3.0}
                      {:id "M" :vol-cm3 27000  :max-kg 10.0}
                      {:id "L" :vol-cm3 64000  :max-kg 25.0}]
        small  [{:id "i1" :vol-cm3 2000 :weight-kg 0.5}
                {:id "i2" :vol-cm3 3000 :weight-kg 1.0}]
        oversz [{:id "j1" :vol-cm3 40000 :weight-kg 12.0}
                {:id "j2" :vol-cm3 40000 :weight-kg 12.0}
                {:id "j3" :vol-cm3 30000 :weight-kg 6.0}]
        r1 (cartonize small carton-types)
        r2 (cartonize oversz carton-types)]
    (println "kuramori 倉守 — cartonization (pack picked items into shipping cartons)")
    (println (format "small order → %d carton(s):" (:count r1)))
    (doseq [{:keys [carton-type items vol-util weight-util]} (:cartons r1)]
      (println (format "  carton %-2s  items %s  vol %.0f%%  wt %.0f%%"
                       carton-type (pr-str items) (* 100.0 vol-util) (* 100.0 weight-util))))
    (println (format "oversize total → %d carton(s) (FFD split into largest type):" (:count r2)))
    (doseq [{:keys [carton-type items vol-util weight-util]} (:cartons r2)]
      (println (format "  carton %-2s  items %s  vol %.0f%%  wt %.0f%%"
                       carton-type (pr-str items) (* 100.0 vol-util) (* 100.0 weight-util))))
    (flush)))
