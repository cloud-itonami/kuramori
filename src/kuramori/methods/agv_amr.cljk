;; kuramori 倉守 — AGV/AMR fleet motion + dispatch + battery core.
;;
;; Warehouse horizontal transport: an AGV (fixed guidepath) or AMR (free-roaming)
;; carries a tote/pallet from a source slot to a destination. This is the planning
;; core behind the `fleet_dispatch` cell.
;;
;; Ports niyaku's PROVEN semantics (ADR-2606082000, agv_transfer.py) — trapezoidal
;; velocity profile, one-way segment-conflict check, greedy LPT dispatch — and
;; extends them for the warehouse with:
;;   * AGV (fixed path, segment reservations) vs AMR (free-roaming, shared-zone yield);
;;   * a battery state-of-charge model + opportunity-charge gate (G2 electric-only);
;;   * a shared-zone speed cap (G5 safety — robots slow near humans).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable. Pure
;; planning compute; dispatches no real vehicle (G1 no-server-key / R0 design+sim).
;;
;; Per ADR-2606142000 (kuramori R0). Clojure-first (the GAP-actor wave).
(ns kuramori.methods.agv-amr)

;; ── vehicle envelope ───────────────────────────────────────────────────────
;; A vehicle is a plain map. `make-vehicle` fills warehouse-realistic defaults.
;;   :kind        :agv (fixed guidepath) | :amr (free-roaming)
;;   :v-max m/s   :a-max m/s² (accel = decel)   :length-m (footprint, segment occupancy)
;;   :battery-kwh capacity   :draw-kw drive draw at cruise   :soc 0..1   :soc-min reserve floor
(defn make-vehicle
  ([] (make-vehicle :amr {}))
  ([kind] (make-vehicle kind {}))
  ([kind overrides]
   (merge {:kind kind
           :v-max (if (= kind :agv) 2.0 1.5)   ; AMRs share human floor → slower default
           :a-max 0.6
           :length-m (if (= kind :agv) 2.4 0.9)
           :battery-kwh 2.0
           :draw-kw 0.5
           :soc 1.0
           :soc-min 0.15}                       ; reserve floor → must charge before crossing it
          overrides)))

;; ── trapezoidal travel-time (ported verbatim semantics from niyaku) ──────────
(defn travel-time
  "Time-optimal travel time over a straight leg under a trapezoidal profile.
   Long leg → accel/cruise/decel; short leg → symmetric triangular peaking below v-max."
  [distance-m {:keys [v-max a-max]}]
  (when (neg? distance-m) (throw (ex-info "distance must be non-negative" {:d distance-m})))
  (if (zero? distance-m)
    0.0
    (let [d-to-vmax (/ (* v-max v-max) a-max)]   ; accel to v-max then decel to 0
      (if (>= distance-m d-to-vmax)
        (let [t-ramp (/ v-max a-max)
              d-cruise (- distance-m d-to-vmax)]
          (+ (* 2.0 t-ramp) (/ d-cruise v-max)))
        (let [vp (Math/sqrt (* a-max distance-m))]  ; triangular peak velocity
          (/ (* 2.0 vp) a-max))))))

;; ── safety: shared-zone yield (G5) ───────────────────────────────────────────
(def ^:const shared-zone-cap-mps
  "Hard speed cap (m/s) when a robot is inside a human-shared zone. Per ADR-2606142000
   G5 — a robot near a worker slows; this is not tunable up by a planner."
  1.5)

(defn effective-vmax
  "v-max clamped to the shared-zone cap when the leg crosses a human-shared zone."
  [{:keys [v-max]} shared?]
  (if shared? (min v-max shared-zone-cap-mps) v-max))

;; ── battery (G2 electric-only) ───────────────────────────────────────────────
(defn leg-energy-kwh
  "Drive energy (kWh) to traverse a leg. Time-on-drive × draw; regenerative braking
   credits a small fraction back (G2 — same regen credit niyaku gives the hoist)."
  [distance-m vehicle]
  (let [t (travel-time distance-m vehicle)
        gross (* (:draw-kw vehicle) (/ t 3600.0))]
    (* gross 0.9)))                              ; 10% regen credit

(defn soc-after
  "SoC (0..1) after a leg, floored at 0."
  [vehicle distance-m]
  (let [used (/ (leg-energy-kwh distance-m vehicle) (:battery-kwh vehicle))]
    (max 0.0 (- (:soc vehicle) used))))

(defn needs-charge?
  "True iff taking this leg would drop the vehicle below its reserve floor (soc-min).
   A planner MUST route such a vehicle to a charger first (opportunity charging)."
  [vehicle distance-m]
  (< (soc-after vehicle distance-m) (:soc-min vehicle)))

;; ── AGV fixed-path segment conflict (ported from niyaku) ─────────────────────
;; A reservation: {:segment s :vehicle-id id :t-in t0 :t-out t1}
(defn reservations-conflict?
  "True iff two reservations share a one-way segment and overlap in time.
   Touching at an endpoint (t-out == t-in) is NOT a conflict. AMRs (no :segment)
   never conflict here — free-roamers are deconflicted by zone occupancy, not lanes."
  [r1 r2]
  (boolean
   (and (:segment r1) (:segment r2)
        (= (:segment r1) (:segment r2))
        (not= (:vehicle-id r1) (:vehicle-id r2))
        (< (:t-in r1) (:t-out r2))
        (< (:t-in r2) (:t-out r1)))))

(defn find-conflicts
  "All conflicting index pairs [i j] (i<j) in a reservation vector."
  [reservations]
  (let [v (vec reservations) n (count v)]
    (for [i (range n) j (range (inc i) n)
          :when (reservations-conflict? (v i) (v j))]
      [i j])))

;; ── greedy LPT dispatch (ported from niyaku) ─────────────────────────────────
;; A move: {:move-id id :distance-m d :shared? bool}
(defn dispatch
  "Greedy makespan-minimising assignment. Each move (longest-first) goes to the
   vehicle that frees up soonest (LPT rule). Shared-zone legs use the yield cap.
   Returns {:assignment {vid [move-id…]} :finish {vid secs} :makespan s}."
  [moves vehicle-ids vehicle]
  (when (empty? vehicle-ids) (throw (ex-info "need at least one vehicle" {})))
  (let [init {:assignment (zipmap vehicle-ids (repeat []))
              :finish (zipmap vehicle-ids (repeat 0.0))}
        ordered (sort-by #(- (:distance-m %)) moves)
        result (reduce
                (fn [acc mv]
                  (let [vid (apply min-key (:finish acc) vehicle-ids)
                        veh (assoc vehicle :v-max (effective-vmax vehicle (:shared? mv)))
                        t (travel-time (:distance-m mv) veh)]
                    (-> acc
                        (update-in [:assignment vid] conj (:move-id mv))
                        (update-in [:finish vid] + t))))
                init ordered)]
    (assoc result :makespan (if (seq (:finish result))
                              (apply max (vals (:finish result)))
                              0.0))))
