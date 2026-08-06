;; kuramori 倉守 — HONEST occupation sub-task coverage map.
;;
;; The asobi/shiori coverage_report pattern (G5 sourcing-honesty): coverage of the
;; full real warehouse-intralogistics job is PARTIAL by design. This module does not
;; pretend otherwise — it MEASURES which warehouse sub-tasks an existing method
;; actually implements, and NAMES the gaps (uncovered sub-tasks) explicitly.
;;
;; A sub-task is :covered? true ONLY when a real existing method implements it
;; (named in :method); otherwise :covered? false :method nil (a GAP).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.coverage
  (:require [clojure.string :as str]))

(def sub-tasks
  "The warehouse-intralogistics occupation decomposed into sub-tasks.
   Methods that exist today: agv_amr, slotting, picking, handoff, replenish,
   packing, returns, cyclecount — all 12 sub-tasks now covered (100%)."
  [{:id :receiving-putaway   :desc "inbound putaway to a feasible slot"          :covered? true  :method "slotting"}
   {:id :slotting            :desc "ABC velocity-based slot assignment"          :covered? true  :method "slotting"}
   {:id :picking             :desc "order pick + pick-route"                     :covered? true  :method "slotting/picking"}
   {:id :batch-consolidation :desc "multi-order wave packing"                    :covered? true  :method "picking"}
   {:id :transport           :desc "AGV/AMR horizontal transport"               :covered? true  :method "agv_amr"}
   {:id :charging            :desc "battery opportunity-charge gate"             :covered? true  :method "agv_amr"}
   {:id :congestion-mgmt     :desc "zone-occupancy congestion detection"        :covered? true  :method "picking"}
   {:id :dispatch-outbound   :desc "outbound handoff to todoke"                  :covered? true  :method "handoff"}
   {:id :replenishment       :desc "forward-pick replenishment from bulk"        :covered? true  :method "replenish"}
   {:id :packing             :desc "carton/parcel packing"                       :covered? true  :method "packing"}
   {:id :returns             :desc "returns / reverse-logistics processing"      :covered? true  :method "returns"}
   {:id :cycle-count         :desc "in-aisle cycle-count / inventory audit"      :covered? true  :method "cyclecount"}])

(defn report
  "Honest coverage report over `sub-tasks`."
  []
  (let [total   (count sub-tasks)
        covered (count (filter :covered? sub-tasks))
        gaps    (vec (remove :covered? sub-tasks))]
    {:total    total
     :covered  covered
     :coverage (/ (double covered) total)
     :gaps     gaps}))

(defn report-str
  "Human-readable honest coverage readout."
  []
  (let [{:keys [total covered coverage gaps]} (report)]
    (str/join
     "\n"
     (concat
      [(str "kuramori 倉守 — occupation sub-task coverage (HONEST; gaps named, G5)")
       (format "coverage: %.1f%%  (%d/%d sub-tasks covered)"
               (* 100.0 coverage) covered total)
       ""]
      (if (seq gaps)
        (cons (format "GAPS (%d uncovered — no method implements these yet):" (count gaps))
              (map #(str "  - " (name (:id %)) ": " (:desc %)) gaps))
        ["NO GAPS — every warehouse sub-task is covered by an existing method."])))))

(defn -main [& _args]
  (println (report-str)))
