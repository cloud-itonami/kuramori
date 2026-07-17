;; kuramori 倉守 — cycle-count / in-aisle inventory audit.
;;
;; A real warehouse never shuts down for a full annual count; it runs CYCLE COUNTING —
;; a continuous in-aisle audit where high-velocity (ABC class A) slots are counted far
;; more often than slow C-class slots, and each count reconciles the system's expected
;; on-hand against what a robot/picker actually counted. This module:
;;   * `count-frequency` — counts-per-period by ABC class (A counted most often, C least),
;;     the cadence that focuses audit effort where errors hurt throughput most;
;;   * `reconcile` — compare slots' :expected vs :counted, returning the discrepancy
;;     list (only mismatched slots, with the signed :delta = counted - expected) plus
;;     inventory-record accuracy = matching / total, in [0,1].
;;
;; G3-aligned: the metric is record accuracy (an EQUIPMENT/inventory metric), never a
;; per-picker pace or biometric ranking.
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.cyclecount)

;; ── count-frequency ──────────────────────────────────────────────────────────────
(def default-cadence
  "Counts per period by ABC class — A is counted most often, C least."
  {:A 12 :B 4 :C 1})

(defn count-frequency
  "Counts-per-period for an ABC `class` (:A :B :C). A > B > C. An unknown class
   degrades to the C cadence (counted at least once). Optional `cadence` override."
  ([class] (count-frequency class default-cadence))
  ([class cadence]
   (get cadence class (:C cadence))))

;; ── reconcile ────────────────────────────────────────────────────────────────────
;; A counted slot: {:slot-id :expected :counted}.
(defn reconcile
  "Compare each slot's system `:expected` on-hand against the physical `:counted`.
   Returns {:discrepancies [{:slot-id :delta}] :accuracy <0..1>} where
     :delta    = :counted - :expected (only emitted for MISMATCHED slots; signed)
     :accuracy = matching / total (record-accuracy fraction in [0,1]; 1.0 if no slots).
   Matching = :expected == :counted."
  [slots]
  (let [total (count slots)
        discrepancies (->> slots
                           (keep (fn [{:keys [slot-id expected counted]}]
                                   (when (not= expected counted)
                                     {:slot-id slot-id :delta (- counted expected)})))
                           vec)
        matching (- total (count discrepancies))]
    {:discrepancies discrepancies
     :accuracy (if (zero? total) 1.0 (/ (double matching) total))}))

;; ── demo ───────────────────────────────────────────────────────────────────────
(defn -main [& _]
  (let [slots [{:slot-id "s-g1" :expected 40 :counted 40}    ; match
               {:slot-id "s-g2" :expected 18 :counted 16}    ; short by 2
               {:slot-id "s-r1" :expected 8  :counted 9}     ; over by 1
               {:slot-id "s-b1" :expected 12 :counted 12}]   ; match
        {:keys [discrepancies accuracy]} (reconcile slots)]
    (println "kuramori 倉守 — cycle-count / in-aisle inventory audit")
    (println (format "  cadence (counts/period): A=%d B=%d C=%d"
                     (count-frequency :A) (count-frequency :B) (count-frequency :C)))
    (println (format "  discrepancies: %d" (count discrepancies)))
    (doseq [{:keys [slot-id delta]} discrepancies]
      (println (format "    %-6s  delta %+d" slot-id delta)))
    (println (format "  record accuracy: %.1f%%" (* 100.0 accuracy)))
    (flush)))
