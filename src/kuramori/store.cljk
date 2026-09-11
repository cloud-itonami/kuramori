(ns kuramori.store
  "SSoT for the kuramori warehouse-robotics actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore` -- atom of EDN. The deterministic default for
                    dev/tests/demo (no deps, no network -- G1).

  The entity is a `floor`: one warehouse floor with its zone map, its
  vehicle envelope, its shared-human zones, and the two authorisation
  facts the constitutional gates turn on (`:actuation-authorised?` for
  G1, `:dividend-cohort-funded?` for G4).

  Unlike `terminal`/5210 -- whose two actuation events (storage commit,
  custody transfer) apply SEQUENTIALLY to one tank -- kuramori's two
  actuation events are INDEPENDENT: a floor may have a dispatch released
  without ever committing a putaway, and vice versa. They therefore get
  two independent double-actuation guards (`:dispatched?` /
  `:putaway-committed?`, dedicated booleans, never a `:status` value --
  the discipline informed by isic-6492's status-lifecycle bug,
  ADR-2607071320).

  The ledger stays append-only: 'which floor had a dispatch released
  without commissioning evidence, which plan was refused for exceeding
  the shared-zone speed cap, which was refused for dropping a vehicle
  below its battery reserve, which putaway was refused as infeasible,
  on what jurisdictional basis, approved by whom' is always a query over
  an immutable log."
  (:require [kuramori.methods.agv-amr :as agv]))

(defprotocol Store
  (floor [s id])
  (all-floors [s])
  (assessment-of [s floor-id] "commissioning assessment (checklist), or nil")
  (plan-of [s floor-id] "the last stored slotting/dispatch plan, or nil")
  (ledger [s])
  (dispatch-history [s] "append-only released-dispatch history")
  (putaway-history [s] "append-only committed-putaway history")
  (floor-already-dispatched? [s floor-id])
  (floor-already-putaway-committed? [s floor-id])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-floors [s floors] "replace/seed the floor directory (map id->floor)"))

;; ----------------------------- demo data -----------------------------

(def default-vehicle
  "The reference AMR envelope every demo floor runs. Built through
  `agv-amr/make-vehicle` so the demo cannot drift from the capability
  library's own defaults."
  (agv/make-vehicle :amr))

(defn demo-data
  "A self-contained floor set covering both actuation lifecycles plus
  each governor check, so the actor + tests run offline.

  Each violation floor isolates exactly ONE failure mode (the rest stay
  clean), following the 'exercise the failure mode directly, never only
  via a happy-path actuation' discipline every sibling governor's demo
  data establishes:

    floor-1  clean, JPN
    floor-2  unknown jurisdiction (\"ATL\") -> no spec-basis
    floor-3  actuation NOT authorised      -> G1
    floor-4  dividend cohort NOT funded    -> G4
    floor-5  tiny battery                  -> G2 (reserve breach)
    floor-6  clean, already dispatched     -> double-actuation guard"
  []
  (let [base {:jurisdiction "JPN"
              :vehicle default-vehicle
              :shared-zones #{"z-pick"}
              :actuation-authorised? true
              :dividend-cohort-funded? true
              :dispatched? false
              :putaway-committed? false
              :status :registered}]
    {:floors
     {"floor-1" (merge base {:id "floor-1" :floor-code "DC-A1"})
      "floor-2" (merge base {:id "floor-2" :floor-code "DC-A2" :jurisdiction "ATL"})
      "floor-3" (merge base {:id "floor-3" :floor-code "DC-A3" :actuation-authorised? false})
      "floor-4" (merge base {:id "floor-4" :floor-code "DC-A4" :dividend-cohort-funded? false})
      "floor-5" (merge base {:id "floor-5" :floor-code "DC-A5"
                             :vehicle (agv/make-vehicle :amr {:soc 0.16 :battery-kwh 0.02})})
      "floor-6" (merge base {:id "floor-6" :floor-code "DC-A6" :dispatched? true})}}))

;; ----------------------------- MemStore (default) -----------------------------

(defn- append [coll x] (conj (vec coll) x))

(defrecord MemStore [a]
  Store
  (floor [_ id] (get-in @a [:floors id]))
  (all-floors [_] (sort-by :id (vals (:floors @a))))
  (assessment-of [_ floor-id] (get-in @a [:assessments floor-id]))
  (plan-of [_ floor-id] (get-in @a [:plans floor-id]))
  (ledger [_] (:ledger @a))
  (dispatch-history [_] (:dispatches @a))
  (putaway-history [_] (:putaways @a))
  (floor-already-dispatched? [_ floor-id]
    (boolean (get-in @a [:floors floor-id :dispatched?])))
  (floor-already-putaway-committed? [_ floor-id]
    (boolean (get-in @a [:floors floor-id :putaway-committed?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :floor/upsert
      (swap! a update-in [:floors (:id value)] merge value)

      :commissioning-assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :plan/set
      (swap! a assoc-in [:plans (first path)] payload)

      :floor/mark-dispatched
      (let [floor-id (first path)
            record {:floor-id floor-id
                    :jurisdiction (:jurisdiction (floor s floor-id))
                    :plan payload}]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:floors floor-id] merge {:dispatched? true})
                       (update :dispatches append record))))
        record)

      :floor/mark-putaway-committed
      (let [floor-id (first path)
            record {:floor-id floor-id
                    :jurisdiction (:jurisdiction (floor s floor-id))
                    :placement payload}]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:floors floor-id] merge {:putaway-committed? true})
                       (update :putaways append record))))
        record)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-floors [s floors] (when (seq floors) (swap! a assoc :floors floors)) s))

(defn seed-db
  "A MemStore seeded with the demo floor set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :plans {}
                           :ledger []
                           :dispatches []
                           :putaways []))))

(defn empty-db
  "An empty MemStore (no floors), for tests that seed their own."
  []
  (->MemStore (atom {:floors {} :assessments {} :plans {}
                     :ledger [] :dispatches [] :putaways []})))
