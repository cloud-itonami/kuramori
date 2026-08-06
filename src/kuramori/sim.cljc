(ns kuramori.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean floor through
  register -> commissioning assessment -> fleet dispatch
  (escalate/approve/commit) -> putaway commit (escalate/approve/commit),
  then shows the HARD-hold scenarios ONE AT A TIME:

    floor-2  jurisdiction with no spec-basis
    floor-3  actuation not authorised                    (G1)
    floor-4  displacement dividend not funded            (G4)
    floor-5  dispatch strands the vehicle below reserve  (G2)
    floor-1  a leg commanded above the shared-zone cap   (G5)
    floor-1  two AGVs reserving the same one-way segment
    floor-1  an infeasible putaway (hazmat, non-rated slot) (G7)
    floor-1  a proposal carrying per-worker pace metrics (G3)
    floor-6  a second dispatch on an already-dispatched floor

  Each scenario isolates exactly ONE failure mode, following the
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline every sibling actor's sim establishes. Hence the
  ordering below: floor-1's refusal scenarios run BEFORE its clean
  dispatch/putaway, so the double-actuation guards do not pile onto
  them.

  ONE exception, stated rather than hidden: floor-2 holds with BOTH
  `:no-spec-basis` and `:evidence-incomplete`, and cannot be made to
  hold with only the first. Its jurisdiction is unknown, so
  `commissioning/assess` on it is itself refused, so no evidence can
  ever be on file. The two violations are structurally inseparable, not
  a sloppy fixture.

  G1 holds throughout: nothing here touches a network or a device. The
  'dispatch' that commits is a plan record in the SSoT, not a robot."
  (:require [langgraph.graph :as g]
            [kuramori.store :as store]
            [kuramori.operation :as op]))

(def supervisor {:actor-id "sup-1" :actor-role :warehouse-supervisor :phase 3})

(def full-checklist
  "Every evidence item `kuramori.facts` requires."
  #{:risk-assessment :protective-stop-verification :operator-competency})

(def clean-legs [{:zone "z-pick" :shared? true :commanded-vmax 1.2}
                 {:zone "z-bulk" :shared? false :commanded-vmax 1.5}])

(def speeding-legs [{:zone "z-pick" :shared? true :commanded-vmax 2.4}])

(def conflicting-reservations
  [{:segment "s-1" :vehicle-id "v-1" :t-in 0.0 :t-out 10.0}
   {:segment "s-1" :vehicle-id "v-2" :t-in 5.0 :t-out 15.0}])

(def moves [{:move-id "m-1" :distance-m 40.0 :shared? true}
            {:move-id "m-2" :distance-m 25.0 :shared? false}])

(def feasible-placement
  {:sku {:id "sku-1" :weight-kg 12 :temp :ambient}
   :slot {:id "slot-1" :max-kg 50 :temps #{:ambient} :dist-from-face 3}})

(def infeasible-placement
  "A hazmat SKU aimed at a slot that is not hazmat-rated -- G7."
  {:sku {:id "sku-9" :weight-kg 12 :temp :ambient :hazmat :flammable}
   :slot {:id "slot-9" :max-kg 50 :temps #{:ambient} :hazmat-rated false
          :dist-from-face 3}})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "sup-1"}}
          {:thread-id tid :resume? true}))

(defn- dispatch-req [subject & [overrides]]
  (merge {:op :fleet/dispatch :subject subject
          :value {:legs clean-legs :moves moves :vehicle-ids ["v-1" "v-2"]
                  :total-distance-m 65.0}}
         overrides))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== floor/register floor-1 (JPN, clean -- auto-commits at phase 3) ==")
    (println (exec-op actor "t1" {:op :floor/register :subject "floor-1"
                                  :value {:jurisdiction "JPN" :floor-code "DC-A1"}}
                      supervisor))

    ;; Evidence first, for every floor whose jurisdiction admits it. floor-2
    ;; is deliberately included and deliberately fails -- see the ns docstring.
    (doseq [[tid fid] [["t2a" "floor-1"] ["t2b" "floor-2"] ["t2c" "floor-3"]
                       ["t2d" "floor-4"] ["t2e" "floor-5"] ["t2f" "floor-6"]]]
      (println (str "\n== commissioning/assess " fid " =="))
      (println (exec-op actor tid {:op :commissioning/assess :subject fid
                                   :value {:filed full-checklist}} supervisor))
      (println (approve! actor tid)))

    ;; --- floor-1 refusals FIRST, while floor-1 is still un-actuated ---

    (println "\n== fleet/dispatch floor-1 with a speeding shared-zone leg (G5 -> HARD hold) ==")
    (println (exec-op actor "t3" (dispatch-req "floor-1"
                                               {:value {:legs speeding-legs
                                                        :moves moves
                                                        :vehicle-ids ["v-1"]
                                                        :total-distance-m 65.0}})
                      supervisor))

    (println "\n== fleet/dispatch floor-1 with conflicting segment reservations -> HARD hold ==")
    (println (exec-op actor "t4" (dispatch-req "floor-1"
                                               {:value {:legs clean-legs
                                                        :moves moves
                                                        :vehicle-ids ["v-1" "v-2"]
                                                        :total-distance-m 65.0
                                                        :reservations conflicting-reservations}})
                      supervisor))

    (println "\n== fleet/dispatch floor-1 carrying per-worker pace metrics (G3 -> HARD hold) ==")
    (println (exec-op actor "t5" (dispatch-req "floor-1"
                                               {:value {:legs clean-legs
                                                        :moves moves
                                                        :vehicle-ids ["v-1"]
                                                        :total-distance-m 65.0
                                                        :picker-pace {"w-1" 142}}})
                      supervisor))

    (println "\n== putaway/commit floor-1 with a hazmat SKU into a non-rated slot (G7 -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :putaway/commit :subject "floor-1"
                                  :value {:placements [infeasible-placement]}}
                      supervisor))

    ;; --- now the clean path, which actuates floor-1 ---

    (println "\n== fleet/dispatch floor-1 (always escalates -- :fleet/dispatch) ==")
    (let [r (exec-op actor "t7" (dispatch-req "floor-1") supervisor)]
      (println r)
      (println "-- human warehouse supervisor approves --")
      (println (approve! actor "t7")))

    (println "\n== putaway/commit floor-1 (always escalates -- :putaway/commit) ==")
    (let [r (exec-op actor "t8" {:op :putaway/commit :subject "floor-1"
                                 :value {:placements [feasible-placement]}}
                     supervisor)]
      (println r)
      (println "-- human warehouse supervisor approves --")
      (println (approve! actor "t8")))

    ;; --- one floor per remaining failure mode ---

    (println "\n== fleet/dispatch floor-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t9" (dispatch-req "floor-2") supervisor))

    (println "\n== fleet/dispatch floor-3 (actuation not authorised, G1 -> HARD hold) ==")
    (println (exec-op actor "t10" (dispatch-req "floor-3") supervisor))

    (println "\n== fleet/dispatch floor-4 (dividend cohort unfunded, G4 -> HARD hold) ==")
    (println (exec-op actor "t11" (dispatch-req "floor-4") supervisor))

    (println "\n== fleet/dispatch floor-5 (battery reserve, G2 -> HARD hold) ==")
    (println (exec-op actor "t12" (dispatch-req "floor-5"
                                                {:value {:legs clean-legs :moves moves
                                                         :vehicle-ids ["v-1"]
                                                         :total-distance-m 400.0}})
                      supervisor))

    (println "\n== fleet/dispatch floor-6 (already dispatched -> HARD hold) ==")
    (println (exec-op actor "t13" (dispatch-req "floor-6") supervisor))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== released dispatches ==")
    (doseq [r (store/dispatch-history db)] (println r))

    (println "\n== committed putaways ==")
    (doseq [r (store/putaway-history db)] (println r))))
