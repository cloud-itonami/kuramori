;; kuramori 倉守 — test suite (clojure.test, babashka-runnable).
;; Run: bb --classpath 20-actors 20-actors/kuramori/methods/test_kuramori.clj
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.test-kuramori
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [kuramori.methods.agv-amr :as fleet]
            [kuramori.methods.slotting :as slot]
            [kuramori.methods.analyze :as az]
            [kuramori.methods.datom-emit :as de]
            [kuramori.methods.picking :as pick]
            [kuramori.methods.packing :as pk]
            [kuramori.methods.handoff :as ho]
            [kuramori.methods.replenish :as rep]
            [kuramori.methods.returns :as ret]
            [kuramori.methods.cyclecount :as cc]
            [kuramori.methods.coverage :as cov]))

;; ── agv_amr ──────────────────────────────────────────────────────────────────
(deftest travel-time-monotonic
  (testing "longer legs take longer; zero leg is free"
    (let [v (fleet/make-vehicle :agv)]
      (is (= 0.0 (fleet/travel-time 0.0 v)))
      (is (< (fleet/travel-time 5.0 v) (fleet/travel-time 50.0 v))))))

(deftest travel-time-rejects-negative
  (is (thrown? clojure.lang.ExceptionInfo (fleet/travel-time -1.0 (fleet/make-vehicle)))))

(deftest trapezoidal-matches-niyaku-closed-form
  (testing "long leg = 2*t_ramp + d_cruise/v (niyaku closed form)"
    (let [v (fleet/make-vehicle :agv {:v-max 2.0 :a-max 0.5})  ; d-to-vmax = 8m
          d 20.0
          expected (+ (* 2.0 (/ 2.0 0.5)) (/ (- 20.0 8.0) 2.0))]
      (is (< (Math/abs (- (fleet/travel-time d v) expected)) 1e-9)))))

(deftest shared-zone-yield-caps-speed
  (testing "G5 — a robot near a human is capped at shared-zone speed"
    (let [v (fleet/make-vehicle :agv {:v-max 3.0})]
      (is (= fleet/shared-zone-cap-mps (fleet/effective-vmax v true)))
      (is (= 3.0 (fleet/effective-vmax v false))))))

(deftest battery-charge-gate
  (testing "G2 — a long leg below reserve floor flags needs-charge"
    (let [low (fleet/make-vehicle :amr {:soc 0.16 :soc-min 0.15 :battery-kwh 0.05})]
      (is (fleet/needs-charge? low 100.0)))
    (let [full (fleet/make-vehicle :amr {:soc 1.0})]
      (is (not (fleet/needs-charge? full 5.0))))))

(deftest agv-segment-conflict
  (testing "same one-way segment + overlapping time = conflict; touching ≠ conflict"
    (let [a {:segment "lane-1" :vehicle-id "agv-1" :t-in 0.0 :t-out 5.0}
          b {:segment "lane-1" :vehicle-id "agv-2" :t-in 3.0 :t-out 8.0}
          c {:segment "lane-1" :vehicle-id "agv-2" :t-in 5.0 :t-out 9.0}
          d {:segment "lane-2" :vehicle-id "agv-2" :t-in 3.0 :t-out 8.0}]
      (is (fleet/reservations-conflict? a b))
      (is (not (fleet/reservations-conflict? a c)))    ; touch at t=5
      (is (not (fleet/reservations-conflict? a d)))    ; different lane
      (is (= [[0 1]] (fleet/find-conflicts [a b c]))))))

(deftest amr-never-segment-conflicts
  (testing "AMRs (no :segment) are not deconflicted by lanes"
    (let [a {:vehicle-id "amr-1" :t-in 0.0 :t-out 5.0}
          b {:vehicle-id "amr-2" :t-in 1.0 :t-out 6.0}]
      (is (not (fleet/reservations-conflict? a b))))))

(deftest dispatch-balances-makespan
  (testing "LPT dispatch spreads load; needs ≥1 vehicle"
    (let [moves [{:move-id "m1" :distance-m 40.0} {:move-id "m2" :distance-m 38.0}
                 {:move-id "m3" :distance-m 5.0}]
          r (fleet/dispatch moves ["a" "b"] (fleet/make-vehicle :amr))]
      (is (= 3 (reduce + (map count (vals (:assignment r))))))
      (is (pos? (:makespan r)))
      (is (thrown? clojure.lang.ExceptionInfo (fleet/dispatch moves [] (fleet/make-vehicle)))))))

;; ── slotting ──────────────────────────────────────────────────────────────────
(deftest abc-classes
  (is (= :A (slot/abc-class 220 {})))
  (is (= :B (slot/abc-class 60 {})))
  (is (= :C (slot/abc-class 5 {}))))

(deftest putaway-respects-weight-temp-hazmat
  (testing "G7 — zone constraints are hard"
    (let [ambient {:id "s" :max-kg 50 :temps #{:ambient} :dist-from-face 5}
          reefer  {:id "r" :max-kg 50 :temps #{:reefer}  :dist-from-face 5}
          haz     {:id "h" :max-kg 50 :temps #{:ambient} :hazmat-rated true
                   :segregate-from #{:oxidizer} :dist-from-face 5}]
      (is (slot/putaway-feasible? {:weight-kg 8 :temp :ambient} ambient))
      (is (not (slot/putaway-feasible? {:weight-kg 80 :temp :ambient} ambient))) ; overweight
      (is (not (slot/putaway-feasible? {:weight-kg 8 :temp :reefer} ambient)))   ; wrong temp
      (is (slot/putaway-feasible? {:weight-kg 8 :temp :reefer} reefer))
      (is (slot/putaway-feasible? {:weight-kg 8 :temp :ambient :hazmat :flammable} haz))
      (is (not (slot/putaway-feasible? {:weight-kg 8 :temp :ambient :hazmat :flammable} ambient))) ; not rated
      (is (not (slot/putaway-feasible? {:weight-kg 8 :temp :ambient :hazmat :oxidizer} haz)))))) ; segregated

(deftest assign-slot-raises-when-infeasible
  (testing "G7 — an infeasible putaway RAISES (never silently forced)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (slot/assign-slot! {:id "x" :weight-kg 999 :temp :ambient}
                                    [{:id "s" :max-kg 50 :temps #{:ambient} :dist-from-face 5}])))))

(deftest golden-zone-packing
  (testing "fastest SKU claims the closest feasible slot"
    (let [skus [{:id "fast" :velocity 200 :weight-kg 5 :temp :ambient}
                {:id "slow" :velocity 1 :weight-kg 5 :temp :ambient}]
          slots [{:id "near" :dist-from-face 5 :max-kg 50 :temps #{:ambient}}
                 {:id "far"  :dist-from-face 50 :max-kg 50 :temps #{:ambient}}]
          r (slot/assign-slots skus slots {})]
      (is (= "near" (get-in r [:placement "fast"])))
      (is (= "far"  (get-in r [:placement "slow"]))))))

(deftest pick-route-returns-to-dock
  (testing "nearest-neighbour route is positive and closes the loop"
    (let [d (slot/pick-route [0 0] [[3 4] [6 8]])]
      (is (pos? d)))))

;; ── analyze + datom_emit (end-to-end over the seed) ──────────────────────────
(def seed (az/load-seed "20-actors/kuramori/data/warehouse.edn"))

(deftest analyze-end-to-end
  (let [res (az/run seed)]
    (testing "every SKU is placed in a feasible slot"
      (is (= (count (:skus seed)) (count (get-in res [:slotting :placement]))))
      ;; the flammable SKU must land in the hazmat-rated slot
      (is (= "s-h1" (get-in res [:slotting :placement "sku-flam"])))
      ;; the cold SKU must land in the reefer slot
      (is (= "s-r1" (get-in res [:slotting :placement "sku-cold"]))))
    (testing "fast mover is class A and lands in the golden zone"
      (is (= :A (get-in res [:abc "sku-fast"])))
      (is (= "z-golden" (->> (:slots seed)
                             (filter #(= (:id %) (get-in res [:slotting :placement "sku-fast"])))
                             first :zone))))
    (testing "dispatch + battery readouts present"
      (is (pos? (get-in res [:dispatch :makespan])))
      (is (contains? (:battery res) :charge-needed)))))

(deftest datom-emit-shape
  (let [res (az/run seed)
        out (de/emit seed res 1)]
    (testing "emits ground :add datoms + transient :derived readouts"
      (is (re-find #":wh\.sku/abc" out))
      (is (re-find #":wh\.slot/in-zone" out))
      (is (re-find #":en/kind :slotted-in" out))
      (is (re-find #":bond/dispatch-makespan" out))
      (is (re-find #":derived\]" out))
      ;; well-formed EDN vector of datoms
      (is (vector? (clojure.edn/read-string out))))))

;; ── picking (multi-order batch consolidation + congestion) ───────────────────
(def orders
  [{:id "o1" :picks ["s-g1" "s-g2" "s-r1"]}
   {:id "o2" :picks ["s-g3" "s-b1"]}
   {:id "o3" :picks ["s-h1"]}])

(deftest consolidate-packs-into-waves
  (testing "FFD packing respects wave capacity; every order placed exactly once"
    (let [waves (pick/consolidate orders 4)
          placed (mapcat :orders waves)]
      (is (= #{"o1" "o2" "o3"} (set placed)))
      (is (= 3 (count placed)))                       ; no order duplicated/dropped
      (is (every? #(<= (count (:picks %)) 4) waves))  ; capacity respected
      ;; total picks preserved across waves
      (is (= 6 (reduce + (map #(count (:picks %)) waves)))))))

(deftest batch-capacity-gate-raises
  (testing "G9 — an order larger than the wave cap RAISES (atomic, never split)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pick/consolidate [{:id "big" :picks ["a" "b" "c" "d" "e"]}] 4)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pick/assert-batch-capacity! [{:id "big" :picks ["a" "b" "c"]}] 2)))))

(deftest tight-cap-one-order-per-wave
  (testing "cap below the second-largest order forces separate waves"
    (let [waves (pick/consolidate orders 3)]
      ;; o1 has 3 picks = cap, so it fills its own wave; o2(2)+o3(1) can share
      (is (>= (count waves) 2))
      (is (every? #(<= (count (:picks %)) 3) waves)))))

(deftest zone-occupancy-sweep
  (testing "peak concurrent occupancy per zone; touching endpoints don't overlap"
    (let [entries [{:zone "z-golden" :t-in 0 :t-out 5}
                   {:zone "z-golden" :t-in 3 :t-out 8}   ; overlaps the first → peak 2
                   {:zone "z-golden" :t-in 8 :t-out 9}   ; touches at 8 → not concurrent
                   {:zone "z-bulk"   :t-in 0 :t-out 5}]]
      (is (= 2 (get (pick/zone-occupancy entries) "z-golden")))
      (is (= 1 (get (pick/zone-occupancy entries) "z-bulk"))))))

(deftest congestion-detection
  (testing "overflow when peak exceeds zone capacity; worst-first"
    (let [entries [{:zone "aisle-1" :t-in 0 :t-out 5}
                   {:zone "aisle-1" :t-in 1 :t-out 6}
                   {:zone "aisle-1" :t-in 2 :t-out 7}    ; peak 3
                   {:zone "aisle-2" :t-in 0 :t-out 5}]]
      (is (pick/congested? entries 2))                  ; 3 > cap 2
      (is (not (pick/congested? entries 3)))            ; 3 ≤ cap 3
      (let [ovf (pick/congestion-overflows entries 2)]
        (is (= "aisle-1" (:zone (first ovf))))
        (is (= 1 (:over (first ovf))))))))

;; ── packing (cartonization: pack picked items into shipping cartons) ─────────
(def carton-types
  [{:id "S" :vol-cm3 8000  :max-kg 3.0}
   {:id "M" :vol-cm3 27000 :max-kg 10.0}
   {:id "L" :vol-cm3 64000 :max-kg 25.0}])

(deftest cartonize-small-order-smallest-fitting-carton
  (testing "a small order fits one SMALLEST carton; utilisation in (0,1]"
    (let [items [{:id "i1" :vol-cm3 2000 :weight-kg 0.5}
                 {:id "i2" :vol-cm3 3000 :weight-kg 1.0}]   ; Σvol 5000 ≤ S, Σwt 1.5 ≤ S
          r (pk/cartonize items carton-types)]
      (is (= 1 (:count r)))
      (is (= "S" (:carton-type (first (:cartons r)))))      ; smallest fitting, not M/L
      (is (= ["i1" "i2"] (:items (first (:cartons r)))))
      (let [{:keys [vol-util weight-util]} (first (:cartons r))]
        (is (and (pos? vol-util) (<= vol-util 1.0)))
        (is (and (pos? weight-util) (<= weight-util 1.0)))))))

(deftest cartonize-oversize-total-multi-carton-split
  (testing "no single carton holds all → FFD split into largest-type cartons; every item placed exactly once"
    (let [items [{:id "j1" :vol-cm3 40000 :weight-kg 12.0}
                 {:id "j2" :vol-cm3 40000 :weight-kg 12.0}  ; j1+j2 vol 80000 > L 64000
                 {:id "j3" :vol-cm3 30000 :weight-kg 6.0}]
          r (pk/cartonize items carton-types)
          placed (mapcat :items (:cartons r))]
      (is (> (:count r) 1))                                 ; multi-carton
      (is (every? #(= "L" (:carton-type %)) (:cartons r)))  ; largest type
      (is (= #{"j1" "j2" "j3"} (set placed)))               ; all items present
      (is (= 3 (count placed)))                             ; each placed exactly once, no dup/drop
      ;; every carton respects both bounds (util ≤ 1.0)
      (is (every? #(and (<= (:vol-util %) 1.0) (<= (:weight-util %) 1.0)) (:cartons r))))))

(deftest cartonize-item-bigger-than-largest-raises
  (testing "a single item exceeding the LARGEST carton RAISES (unpackable; no phantom pack)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pk/cartonize [{:id "huge" :vol-cm3 99999 :weight-kg 1.0}] carton-types))) ; volume over L
    (is (thrown? clojure.lang.ExceptionInfo
                 (pk/cartonize [{:id "heavy" :vol-cm3 100 :weight-kg 99.0}] carton-types))))) ; weight over L

(deftest cartonize-weight-vs-volume-bound-selection
  (testing "carton choice is bounded by weight in one case and by volume in the other"
    ;; weight-bound: tiny volume but heavy → needs M (S max-kg 3.0 too small), volume alone would fit S
    (let [wt-bound [{:id "w1" :vol-cm3 100 :weight-kg 4.0}]
          r (pk/cartonize wt-bound carton-types)]
      (is (= 1 (:count r)))
      (is (= "M" (:carton-type (first (:cartons r)))))      ; S rejected on weight, not volume
      (is (= 0 (int (* 100 (:vol-util (first (:cartons r))))))) ; volume barely used
      (is (> (:weight-util (first (:cartons r))) 0.0)))
    ;; volume-bound: light but bulky → needs M (S vol 8000 too small), weight alone would fit S
    (let [vol-bound [{:id "v1" :vol-cm3 20000 :weight-kg 0.5}]
          r (pk/cartonize vol-bound carton-types)]
      (is (= 1 (:count r)))
      (is (= "M" (:carton-type (first (:cartons r)))))      ; S rejected on volume, not weight
      (is (and (pk/fits? (first vol-bound) {:id "M" :vol-cm3 27000 :max-kg 10.0})
               (not (pk/fits? (first vol-bound) {:id "S" :vol-cm3 8000 :max-kg 3.0})))))))

;; ── handoff (cross-actor chain edges: niyaku→kuramori→todoke) ────────────────
(deftest inbound-from-niyaku
  (testing "niyaku discharge → kuramori putaway intents, source-attributed"
    (let [hs (ho/inbound-handoff [{:box-id "b1" :sku-id "sku-fast" :weight-kg 8 :temp :ambient}
                                  {:box-id "b2" :sku-id "sku-cold" :weight-kg 9 :temp :reefer}])]
      (is (= 2 (count hs)))
      (is (every? #(= "niyaku" (:from-actor %)) hs))
      (is (every? #(= "kuramori" (:to-actor %)) hs))
      (is (= :inbound (:kind (first hs))))
      (is (= "sku-fast" (get-in (first hs) [:payload :sku-id]))))))

(deftest outbound-to-todoke
  (testing "completed picked order → todoke last-mile delivery intent"
    (let [h (ho/outbound-handoff {:id "ord-1" :picks ["s-g1" "s-r1" "s-b1"]})]
      (is (= "kuramori" (:from-actor h)))
      (is (= "todoke" (:to-actor h)))
      (is (= :outbound (:kind h)))
      (is (= 3 (get-in h [:payload :parcel-count]))))))

(deftest handoff-provenance-gate
  (testing "G10 — an orphan handoff (no source/destination) RAISES"
    (is (thrown? clojure.lang.ExceptionInfo (ho/assert-handoff! {:id "x" :to-actor "todoke"})))
    (is (thrown? clojure.lang.ExceptionInfo (ho/assert-handoff! {:id "x" :from-actor "kuramori"})))
    (is (= "kuramori" (:from-actor (ho/assert-handoff! {:id "x" :from-actor "kuramori" :to-actor "todoke"}))))))

(deftest handoff-emit-shape
  (testing "emits well-formed EDN :handoff/* 縁 with actor provenance on every edge"
    (let [hs (conj (ho/inbound-handoff [{:box-id "b1" :sku-id "s" :weight-kg 8 :temp :ambient}])
                   (ho/outbound-handoff {:id "ord-1" :picks ["s-g1"]}))
          out (ho/emit hs 1)]
      (is (re-find #":handoff/from-actor" out))
      (is (re-find #":handoff/to-actor" out))
      (is (re-find #"en\.handoff\.niyaku\.kuramori\." out))
      (is (re-find #"en\.handoff\.kuramori\.todoke\." out))
      (is (vector? (clojure.edn/read-string out))))))

;; ── replenish (forward-pick replenishment from bulk) ─────────────────────────
(def fwd-slots
  [{:slot-id "f-g1" :sku-id "sku-fast" :qty 4  :min 10 :max 40}   ; below min
   {:slot-id "f-g2" :sku-id "sku-med"  :qty 18 :min 12 :max 36}   ; healthy
   {:slot-id "f-r1" :sku-id "sku-cold" :qty 2  :min 8  :max 24}]) ; below min

(def bulk-src
  [{:bulk-id "blk-A" :sku-id "sku-fast" :qty 200}
   {:bulk-id "blk-B" :sku-id "sku-cold" :qty 13}
   {:bulk-id "blk-C" :sku-id "sku-med"  :qty 50}])

(deftest needs-replenish-only-below-min
  (testing "only slots whose qty < min are returned"
    (let [needy (rep/needs-replenish fwd-slots)]
      (is (= #{"f-g1" "f-r1"} (set (map :slot-id needy))))
      (is (= 2 (count needy))))))

(deftest replenish-plan-refills-toward-max-respecting-case-pack
  (testing "refill = round-down(min(max-qty, available)) to case-pack quantum"
    (let [plan (rep/replenish-plan fwd-slots bulk-src {:case-pack 6})
          by-slot (into {} (map (juxt :slot-id identity) plan))]
      ;; f-g1: want = 40-4 = 36, available 200 → min 36, /6 = 36
      (is (= 36 (:qty (by-slot "f-g1"))))
      (is (= "blk-A" (:from-bulk (by-slot "f-g1"))))
      ;; f-r1: want = 24-2 = 22, available 13 → min 13, round down to case-pack 6 → 12
      (is (= 12 (:qty (by-slot "f-r1"))))
      (is (= "blk-B" (:from-bulk (by-slot "f-r1"))))
      ;; healthy slot is never planned
      (is (nil? (by-slot "f-g2"))))))

(deftest replenish-plan-hard-stockout-raises
  (testing "G — a SKU absent from all bulk RAISES (no phantom replenishment)"
    (let [slots [{:slot-id "f-x1" :sku-id "sku-ghost" :qty 0 :min 5 :max 20}]]
      (is (thrown? clojure.lang.ExceptionInfo (rep/replenish-plan slots bulk-src))))))

(deftest replenish-plan-fully-stocked-is-empty
  (testing "a face all at/above min yields an empty plan"
    (let [stocked [{:slot-id "f-a" :sku-id "sku-fast" :qty 40 :min 10 :max 40}
                   {:slot-id "f-b" :sku-id "sku-cold" :qty 12 :min 8  :max 24}]]
      (is (empty? (rep/replenish-plan stocked bulk-src {:case-pack 6}))))))

;; ── returns (reverse logistics: disposition by condition grade) ──────────────
(deftest disposition-by-condition-grade
  (testing "grade ≥4 → restock, 2-3 → refurbish, 1 → scrap"
    (is (= :restock   (ret/disposition {:id "a" :sku-id "s" :condition-grade 5})))
    (is (= :restock   (ret/disposition {:id "b" :sku-id "s" :condition-grade 4})))
    (is (= :refurbish (ret/disposition {:id "c" :sku-id "s" :condition-grade 3})))
    (is (= :refurbish (ret/disposition {:id "d" :sku-id "s" :condition-grade 2})))
    (is (= :scrap     (ret/disposition {:id "e" :sku-id "s" :condition-grade 1})))))

(deftest disposition-missing-grade-raises
  (testing "no-blind-restock gate — an ungraded return RAISES (never silently restocked)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ret/disposition {:id "x" :sku-id "s"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ret/disposition {:id "x" :sku-id "s" :condition-grade nil})))))

(deftest process-returns-buckets-correctly
  (testing "items fold into restock/refurbish/scrap buckets + restock-plan units"
    (let [items [{:id "r1" :sku-id "s" :condition-grade 5}    ; restock
                 {:id "r2" :sku-id "s" :condition-grade 4}    ; restock
                 {:id "r3" :sku-id "s" :condition-grade 3}    ; refurbish
                 {:id "r4" :sku-id "s" :condition-grade 1}]   ; scrap
          r (ret/process-returns items)]
      (is (= #{"r1" "r2"} (set (map :id (:restock r)))))
      (is (= ["r3"] (mapv :id (:refurbish r))))
      (is (= ["r4"] (mapv :id (:scrap r))))
      (is (= 2 (get-in r [:restock-plan :units])))            ; 2 units back to pick face
      ;; every item placed exactly once across the three buckets
      (is (= 4 (+ (count (:restock r)) (count (:refurbish r)) (count (:scrap r))))))))

;; ── cyclecount (in-aisle inventory audit) ────────────────────────────────────
(deftest count-frequency-A-over-B-over-C
  (testing "A counted more often than B more often than C; unknown degrades to C"
    (is (> (cc/count-frequency :A) (cc/count-frequency :B)))
    (is (> (cc/count-frequency :B) (cc/count-frequency :C)))
    (is (= (cc/count-frequency :C) (cc/count-frequency :Z)))   ; unknown → C cadence
    (is (= 12 (cc/count-frequency :A {:A 12 :B 4 :C 1})))))

(deftest reconcile-flags-only-mismatched-slots
  (testing "only slots where counted ≠ expected appear, with signed delta"
    (let [slots [{:slot-id "s1" :expected 40 :counted 40}      ; match
                 {:slot-id "s2" :expected 18 :counted 16}      ; short -2
                 {:slot-id "s3" :expected 8  :counted 9}]      ; over +1
          {:keys [discrepancies]} (cc/reconcile slots)
          by-slot (into {} (map (juxt :slot-id :delta) discrepancies))]
      (is (= 2 (count discrepancies)))                         ; only the 2 mismatches
      (is (nil? (get by-slot "s1")))                           ; matching slot not flagged
      (is (= -2 (get by-slot "s2")))
      (is (= 1  (get by-slot "s3"))))))

(deftest reconcile-accuracy-is-matching-over-total-in-unit-interval
  (testing "accuracy = matching/total, in [0,1]; all-match = 1.0, empty = 1.0"
    (let [slots [{:slot-id "s1" :expected 40 :counted 40}      ; match
                 {:slot-id "s2" :expected 18 :counted 16}      ; mismatch
                 {:slot-id "s3" :expected 8  :counted 8}       ; match
                 {:slot-id "s4" :expected 12 :counted 12}]     ; match
          {:keys [accuracy]} (cc/reconcile slots)]
      (is (= (/ 3.0 4) accuracy))                              ; 3 of 4 match
      (is (and (>= accuracy 0.0) (<= accuracy 1.0)))
      (is (= 1.0 (:accuracy (cc/reconcile [{:slot-id "a" :expected 1 :counted 1}]))))
      (is (= 1.0 (:accuracy (cc/reconcile [])))))))            ; empty = perfect

;; ── coverage (HONEST occupation sub-task map; G5 sourcing-honesty) ───────────
(deftest coverage-fraction-is-covered-over-total
  (testing "coverage fraction is in (0,1] and equals covered/total"
    (let [{:keys [total covered coverage]} (cov/report)]
      (is (pos? coverage))
      (is (<= coverage 1.0))
      (is (= coverage (/ (double covered) total))))))

(deftest coverage-gaps-are-exactly-the-uncovered
  (testing "G5 — :gaps are exactly the :covered? false sub-tasks (honest measurement)"
    (let [{:keys [gaps]} (cov/report)
          uncovered (remove :covered? cov/sub-tasks)]
      (is (= (set (map :id gaps)) (set (map :id uncovered))))
      (is (every? (complement :covered?) gaps)))))

(deftest coverage-is-complete
  (testing "all 12 warehouse sub-tasks are now covered (100%); no gaps remain"
    (let [{:keys [total covered coverage gaps]} (cov/report)]
      (is (= 12 total))
      (is (= total covered))
      (is (= 1.0 coverage))
      (is (empty? gaps)))))

(deftest covered-sub-tasks-name-a-method
  (testing "every :covered? true sub-task names a non-nil :method (and gaps name none)"
    (doseq [st cov/sub-tasks]
      (if (:covered? st)
        (is (and (:method st) (not (str/blank? (:method st))))
            (str (:id st) " is covered but names no method"))
        (is (nil? (:method st))
            (str (:id st) " is a gap but names a method"))))))

;; ── run-day full-pipeline integration (R1 — methods compose, not just coexist) ─
(deftest run-day-exercises-all-domain-methods
  (testing "the full warehouse-day pipeline threads through every domain method"
    (let [res (az/run-day seed)]
      (is (= #{"handoff" "slotting" "replenish" "picking" "packing" "agv_amr" "returns" "cyclecount"}
             (:methods res)))
      (is (>= (count (:methods res)) 8))
      (is (contains? res :slotting))
      (is (pos? (get-in res [:dispatch :makespan])))
      (is (= 2 (count (get-in res [:day :inbound]))))
      (is (pos? (:count (get-in res [:day :cartons]))))
      (is (= "todoke" (:to-actor (get-in res [:day :outbound]))))
      (is (= 1 (count (:scrap (get-in res [:day :returns])))))
      (is (<= 0.0 (:accuracy (get-in res [:day :cyclecount])) 1.0)))))

(deftest run-day-report-lists-pipeline
  (let [s (az/report-day-str (az/run-day seed))]
    (is (re-find #"methods exercised" s))
    (is (re-find #"packing" s))
    (is (re-find #"cyclecount" s))))

(deftest datom-emit-day-captures-full-day
  (testing "the canonical Datom log records the WHOLE day, not just slotting"
    (let [day (az/run-day seed)
          out (de/emit-day seed day 1)]
      ;; base GROUND still present
      (is (re-find #":wh\.sku/abc" out))
      ;; day operations now in the canonical log
      (is (re-find #":handoff/from-actor" out))           ; inbound + outbound handoffs
      (is (re-find #"en\.handoff\.kuramori\.todoke" out))
      (is (re-find #":wh\.replenish/qty" out))            ; replenishment moves
      (is (re-find #":wh\.return/disposition :scrap" out)) ; returns disposition (by id)
      (is (re-find #":bond/cyclecount-accuracy" out))     ; day metrics (DERIVED)
      (is (re-find #":bond/pick-waves" out))
      ;; returns entity is the item id, not a map literal
      (is (re-find #"\"ret-3\" :wh\.return/disposition :scrap" out))
      ;; still a well-formed EDN vector
      (is (vector? (clojure.edn/read-string out)))
      ;; emit-day is a superset of base emit
      (is (> (count (clojure.edn/read-string out))
             (count (clojure.edn/read-string (de/emit seed day 1))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kuramori.methods.test-kuramori)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
