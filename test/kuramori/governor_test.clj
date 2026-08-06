(ns kuramori.governor-test
  "The Warehouse Robotics Governor's whole job is to REFUSE. These tests
  are the refusal demonstration: for every HARD check there is a case
  that must hold, and a paired clean case that must not -- a governor
  that never says no, and a governor that always says no, are both
  useless, so both directions are asserted.

  The constitutional gates G1-G8 in CLAUDE.md were prose before
  `kuramori.governor` existed. Each test below names the gate it pins."
  (:require [clojure.test :refer [deftest testing is]]
            [kuramori.governor :as governor]
            [kuramori.facts :as facts]
            [kuramori.store :as store]
            [kuramori.methods.agv-amr :as agv]))

(def ctx {:actor-id "sup-1" :actor-role :warehouse-supervisor :phase 3})

(def full-checklist
  #{:risk-assessment :protective-stop-verification :operator-competency})

(defn- db-with-assessment
  "A seeded store where `floor-ids` have a complete commissioning
  assessment on file, so tests can isolate a single OTHER failure mode."
  [& floor-ids]
  (let [db (store/seed-db)]
    (doseq [id floor-ids]
      (store/commit-record! db {:effect :commissioning-assessment/set
                                :path [id]
                                :payload {:checklist full-checklist}}))
    db))

(def clean-legs [{:zone "z-pick" :shared? true :commanded-vmax 1.2}])

(defn- dispatch-proposal
  ([] (dispatch-proposal {}))
  ([value-overrides]
   {:summary "dispatch" :rationale "r"
    :cites (:sources (facts/jurisdiction "JPN"))
    :effect :floor/mark-dispatched
    :stake :fleet/dispatch
    :value (merge {:legs clean-legs :total-distance-m 40.0} value-overrides)
    :confidence 0.9}))

(defn- rules [verdict] (set (map :rule (:violations verdict))))

;; ---------------------------------------------------------------- baseline

(deftest clean-dispatch-is-not-hard-held
  (testing "a clean, fully-evidenced dispatch produces NO hard violation"
    (let [db (db-with-assessment "floor-1")
          v (governor/check {:op :fleet/dispatch :subject "floor-1"}
                            ctx (dispatch-proposal) db)]
      (is (false? (:hard? v)) (str "unexpected violations: " (rules v)))
      ;; still escalates: actuation is always a human call
      (is (true? (:high-stakes? v)))
      (is (true? (:escalate? v)))
      (is (false? (:ok? v))))))

;; ---------------------------------------------------------------- spec-basis

(deftest unknown-jurisdiction-is-refused
  (testing "a floor whose jurisdiction is not in kuramori.facts has no spec-basis"
    (let [db (db-with-assessment "floor-2")
          v (governor/check {:op :fleet/dispatch :subject "floor-2"}
                            ctx (dispatch-proposal) db)]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :no-spec-basis)))))

(deftest empty-cites-is-refused
  (testing "a proposal citing nothing is refused even in a known jurisdiction"
    (let [db (db-with-assessment "floor-1")
          v (governor/check {:op :fleet/dispatch :subject "floor-1"}
                            ctx (assoc (dispatch-proposal) :cites []) db)]
      (is (contains? (rules v) :no-spec-basis)))))

;; ---------------------------------------------------------------- evidence

(deftest missing-commissioning-evidence-is-refused
  (testing "no assessment on file -> refused"
    (let [db (store/seed-db)
          v (governor/check {:op :fleet/dispatch :subject "floor-1"}
                            ctx (dispatch-proposal) db)]
      (is (contains? (rules v) :evidence-incomplete))))
  (testing "a PARTIAL checklist is refused -- absence of a rule is not permission"
    (let [db (store/seed-db)]
      (store/commit-record! db {:effect :commissioning-assessment/set
                                :path ["floor-1"]
                                :payload {:checklist #{:risk-assessment}}})
      (is (contains? (rules (governor/check {:op :fleet/dispatch :subject "floor-1"}
                                            ctx (dispatch-proposal) db))
                     :evidence-incomplete)))))

;; ---------------------------------------------------------------- G1

(deftest unauthorised-actuation-is-refused
  (testing "G1 -- R0 is design+sim only; a floor without explicit actuation
            authorisation cannot release a dispatch"
    (let [db (db-with-assessment "floor-3")
          v (governor/check {:op :fleet/dispatch :subject "floor-3"}
                            ctx (dispatch-proposal) db)]
      (is (contains? (rules v) :actuation-not-authorised)))))

;; ---------------------------------------------------------------- G4

(deftest unfunded-dividend-cohort-is-refused
  (testing "G4 -- displacement must be coupled to a funded dividend cohort"
    (let [db (db-with-assessment "floor-4")
          v (governor/check {:op :fleet/dispatch :subject "floor-4"}
                            ctx (dispatch-proposal) db)]
      (is (contains? (rules v) :dividend-uncoupled)))))

;; ---------------------------------------------------------------- G5

(deftest shared-zone-speeding-is-refused
  (testing "G5 -- a leg commanded above the shared-zone cap is refused, and the
            cap the governor uses is the capability library's own constant"
    (let [db (db-with-assessment "floor-1")
          over (+ agv/shared-zone-cap-mps 0.5)
          v (governor/check
             {:op :fleet/dispatch :subject "floor-1"}
             ctx (dispatch-proposal
                  {:legs [{:zone "z-pick" :shared? true :commanded-vmax over}]}) db)]
      (is (contains? (rules v) :shared-zone-speed))))
  (testing "the SAME speed on a NON-shared leg is fine (the cap is zone-scoped,
            not a global speed limit)"
    (let [db (db-with-assessment "floor-1")
          v (governor/check
             {:op :fleet/dispatch :subject "floor-1"}
             ctx (dispatch-proposal
                  {:legs [{:zone "z-bulk" :shared? false :commanded-vmax 1.5}]}) db)]
      (is (not (contains? (rules v) :shared-zone-speed))))))

;; ---------------------------------------------------------------- G2

(deftest battery-reserve-breach-is-refused
  (testing "G2 -- a dispatch that would strand a vehicle below its reserve floor"
    (let [db (db-with-assessment "floor-5")
          f (store/floor db "floor-5")]
      ;; precondition: the capability library itself says this leg needs a charge
      (is (true? (agv/needs-charge? (:vehicle f) 400.0)))
      (let [v (governor/check {:op :fleet/dispatch :subject "floor-5"}
                              ctx (dispatch-proposal {:total-distance-m 400.0}) db)]
        (is (contains? (rules v) :battery-reserve))))))

;; ---------------------------------------------------------------- segments

(deftest overlapping-segment-reservations-are-refused
  (testing "two vehicles on the same one-way segment at overlapping times"
    (let [db (db-with-assessment "floor-1")
          v (governor/check
             {:op :fleet/dispatch :subject "floor-1"}
             ctx (dispatch-proposal
                  {:reservations [{:segment "s-1" :vehicle-id "v-1" :t-in 0.0 :t-out 10.0}
                                  {:segment "s-1" :vehicle-id "v-2" :t-in 5.0 :t-out 15.0}]}) db)]
      (is (contains? (rules v) :segment-conflict))))
  (testing "touching at an endpoint is NOT a conflict"
    (let [db (db-with-assessment "floor-1")
          v (governor/check
             {:op :fleet/dispatch :subject "floor-1"}
             ctx (dispatch-proposal
                  {:reservations [{:segment "s-1" :vehicle-id "v-1" :t-in 0.0 :t-out 10.0}
                                  {:segment "s-1" :vehicle-id "v-2" :t-in 10.0 :t-out 20.0}]}) db)]
      (is (not (contains? (rules v) :segment-conflict))))))

;; ---------------------------------------------------------------- G7

(deftest infeasible-putaway-is-refused
  (let [putaway (fn [placements]
                  {:summary "putaway" :rationale "r"
                   :cites (:sources (facts/jurisdiction "JPN"))
                   :effect :floor/mark-putaway-committed
                   :stake :putaway/commit
                   :value {:placements placements}
                   :confidence 0.9})]
    (testing "G7 -- a hazmat SKU into a non-hazmat-rated slot"
      (let [db (db-with-assessment "floor-1")
            v (governor/check {:op :putaway/commit :subject "floor-1"} ctx
                              (putaway [{:sku {:id "sku-9" :weight-kg 12 :temp :ambient
                                               :hazmat :flammable}
                                         :slot {:id "slot-9" :max-kg 50 :temps #{:ambient}
                                                :hazmat-rated false :dist-from-face 3}}])
                              db)]
        (is (contains? (rules v) :putaway-infeasible))))
    (testing "an over-weight SKU is refused by the same check"
      (let [db (db-with-assessment "floor-1")
            v (governor/check {:op :putaway/commit :subject "floor-1"} ctx
                              (putaway [{:sku {:id "sku-h" :weight-kg 900 :temp :ambient}
                                         :slot {:id "slot-1" :max-kg 50 :temps #{:ambient}
                                                :dist-from-face 3}}])
                              db)]
        (is (contains? (rules v) :putaway-infeasible))))
    (testing "a wrong temperature class is refused by the same check"
      (let [db (db-with-assessment "floor-1")
            v (governor/check {:op :putaway/commit :subject "floor-1"} ctx
                              (putaway [{:sku {:id "sku-r" :weight-kg 5 :temp :reefer}
                                         :slot {:id "slot-1" :max-kg 50 :temps #{:ambient}
                                                :dist-from-face 3}}])
                              db)]
        (is (contains? (rules v) :putaway-infeasible))))
    (testing "a feasible placement is NOT refused"
      (let [db (db-with-assessment "floor-1")
            v (governor/check {:op :putaway/commit :subject "floor-1"} ctx
                              (putaway [{:sku {:id "sku-1" :weight-kg 12 :temp :ambient}
                                         :slot {:id "slot-1" :max-kg 50 :temps #{:ambient}
                                                :dist-from-face 3}}])
                              db)]
        (is (not (contains? (rules v) :putaway-infeasible)))))))

;; ---------------------------------------------------------------- G3

(deftest worker-surveillance-is-refused
  (testing "G3 -- per-worker pace metrics are refused outright"
    (let [db (db-with-assessment "floor-1")]
      (doseq [k [:worker-metrics :picker-pace :biometric :per-worker-ranking
                 :worker-productivity-ranking]]
        (let [v (governor/check {:op :fleet/dispatch :subject "floor-1"}
                                ctx (dispatch-proposal {k {"w-1" 142}}) db)]
          (is (contains? (rules v) :worker-surveillance)
              (str "expected " k " to be refused")))))))

;; ---------------------------------------------------------------- double-actuation

(deftest double-dispatch-is-refused
  (testing "a floor already dispatched cannot be dispatched again"
    (let [db (db-with-assessment "floor-6")
          v (governor/check {:op :fleet/dispatch :subject "floor-6"}
                            ctx (dispatch-proposal) db)]
      (is (contains? (rules v) :already-dispatched)))))

(deftest double-putaway-commit-is-refused
  (testing "a floor already putaway-committed cannot be committed again"
    (let [db (db-with-assessment "floor-1")]
      (store/commit-record! db {:effect :floor/mark-putaway-committed
                                :path ["floor-1"] :payload {}})
      (let [v (governor/check {:op :putaway/commit :subject "floor-1"}
                              ctx {:summary "p" :rationale "r"
                                   :cites (:sources (facts/jurisdiction "JPN"))
                                   :effect :floor/mark-putaway-committed
                                   :stake :putaway/commit
                                   :value {:placements []}
                                   :confidence 0.9} db)]
        (is (contains? (rules v) :already-putaway-committed))))))

;; ---------------------------------------------------------------- handoff

(deftest handoff-absence-is-not-a-violation-but-malformation-is
  (let [db (db-with-assessment "floor-1")
        chk (fn [p] (rules (governor/check {:op :fleet/dispatch :subject "floor-1"}
                                           ctx p db)))]
    (testing "absence of :handoff is never itself a violation"
      (is (not (contains? (chk (dispatch-proposal)) :handoff-malformed))))
    (testing "a PRESENT but malformed :handoff is refused"
      (is (contains? (chk (dispatch-proposal {:handoff {:id "h-1"}}))
                     :handoff-malformed)))
    (testing "a well-formed :handoff passes"
      (is (not (contains?
                (chk (dispatch-proposal
                      {:handoff {:id "h-1" :source-actor "kuramori"
                                 :batch-id "b-1" :sku-id "sku-1"
                                 :quantity-units 12
                                 :dispatched-at-iso "2026-08-06T00:00:00Z"}}))
                :handoff-malformed))))
    (testing "a non-positive quantity is malformed"
      (is (contains?
           (chk (dispatch-proposal
                 {:handoff {:id "h-1" :source-actor "kuramori"
                            :batch-id "b-1" :sku-id "sku-1"
                            :quantity-units 0
                            :dispatched-at-iso "2026-08-06T00:00:00Z"}}))
           :handoff-malformed)))))

;; ---------------------------------------------------------------- soft gate

(deftest low-confidence-escalates-but-does-not-hold
  (testing "the confidence floor is SOFT -- it asks a human, it does not refuse"
    (let [db (db-with-assessment "floor-1")
          v (governor/check {:op :fleet/dispatch :subject "floor-1"}
                            ctx (assoc (dispatch-proposal) :confidence 0.1) db)]
      (is (false? (:hard? v)))
      (is (true? (:escalate? v))))))

(deftest hard-violations-are-never-merely-escalated
  (testing "a HARD violation must not degrade into an approvable escalation --
            a human approver cannot override compliance"
    (let [db (db-with-assessment "floor-3")
          v (governor/check {:op :fleet/dispatch :subject "floor-3"}
                            ctx (dispatch-proposal) db)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (false? (:ok? v))))))

;; ---------------------------------------------------------------- hold fact

(deftest hold-fact-carries-the-rule-basis
  (let [db (db-with-assessment "floor-3")
        req {:op :fleet/dispatch :subject "floor-3"}
        v (governor/check req ctx (dispatch-proposal) db)
        f (governor/hold-fact req ctx v)]
    (is (= :governor-hold (:t f)))
    (is (= :hold (:disposition f)))
    (is (= "floor-3" (:subject f)))
    (is (contains? (set (:basis f)) :actuation-not-authorised))))
