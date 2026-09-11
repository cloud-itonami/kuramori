;; kuramori 倉守 — warehouse slotting + putaway feasibility + pick-route core.
;;
;;   * ABC velocity-based slotting — high-velocity SKUs land in the "golden zone"
;;     (closest to the pick face) to minimise total picker/robot travel;
;;   * putaway feasibility — a SKU may only enter a slot whose zone admits its
;;     weight, temperature class, and hazmat segregation (G7 — an infeasible
;;     putaway RAISES, it is never silently forced to "make a plan fit", mirroring
;;     niyaku's StowError discipline);
;;   * pick-route — nearest-neighbour traversal length for an order's pick list.
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.slotting)

;; ── ABC velocity classing ────────────────────────────────────────────────────
(defn abc-class
  "Classify a SKU by pick-velocity (picks per period). A = fastest movers."
  [velocity {:keys [a-cut b-cut] :or {a-cut 100 b-cut 20}}]
  (cond (>= velocity a-cut) :A
        (>= velocity b-cut) :B
        :else :C))

;; ── putaway feasibility (G7 — no silent force) ───────────────────────────────
(defn putaway-feasible?
  "True iff `slot` can physically + lawfully hold `sku`. Checks:
     weight   — sku :weight-kg ≤ slot :max-kg
     temp     — sku :temp class ∈ slot :temps (e.g. #{:ambient} or #{:reefer})
     hazmat   — sku :hazmat class not in the slot zone's :segregate-from set, and a
                hazmat SKU only enters a hazmat-rated slot.
   Returns true/false; never throws (use `assign-slot!` for the raising variant)."
  [sku slot]
  (and (<= (:weight-kg sku 0) (:max-kg slot 0))
       (contains? (set (:temps slot #{:ambient})) (:temp sku :ambient))
       (let [hz (:hazmat sku)]
         (if hz
           (and (:hazmat-rated slot false)
                (not (contains? (set (:segregate-from slot #{})) hz)))
           true))))

(defn assign-slot!
  "Pick the feasible open slot with the SMALLEST :dist-from-face for `sku`
   (golden-zone packing). Returns the slot id; RAISES if none is feasible — an
   infeasible request must surface, not be forced (G7)."
  [sku open-slots]
  (let [feasible (filter #(putaway-feasible? sku %) open-slots)]
    (when (empty? feasible)
      (throw (ex-info "no feasible slot for SKU" {:sku (:id sku)})))
    (:id (apply min-key #(:dist-from-face % Double/MAX_VALUE) feasible))))

(defn assign-slots
  "Velocity-greedy slotting: fastest SKUs claim the closest feasible slots first.
   Returns {:placement {sku-id slot-id} :weighted-travel <Σ velocity×dist>}.
   Slots are consumed as they are claimed (one SKU per slot here)."
  [skus slots abc-opts]
  (let [ordered (sort-by #(- (:velocity % 0)) skus)
        by-id (into {} (map (juxt :id identity) slots))]
    (loop [[sku & more] ordered
           open slots
           placement {}
           travel 0.0]
      (if (nil? sku)
        {:placement placement :weighted-travel travel}
        (let [slot-id (assign-slot! sku open)
              slot (by-id slot-id)]
          (recur more
                 (remove #(= (:id %) slot-id) open)
                 (assoc placement (:id sku) slot-id)
                 (+ travel (* (:velocity sku 0) (:dist-from-face slot 0)))))))))

;; ── pick-route (nearest-neighbour traversal length) ──────────────────────────
(defn- dist [[x1 y1] [x2 y2]]
  (Math/sqrt (+ (* (- x1 x2) (- x1 x2)) (* (- y1 y2) (- y1 y2)))))

(defn pick-route
  "Nearest-neighbour route length from the dock origin through every pick coord
   and back to the dock. `coords` is a seq of [x y]. Returns total distance (m).
   This is the picking-leg input to fleet dispatch."
  [origin coords]
  (loop [here origin
         remaining (set coords)
         total 0.0]
    (if (empty? remaining)
      (+ total (dist here origin))                ; return to dock
      (let [nxt (apply min-key #(dist here %) remaining)]
        (recur nxt (disj remaining nxt) (+ total (dist here nxt)))))))
