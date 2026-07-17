;; kuramori 倉守 — forward-pick replenishment from bulk/reserve.
;;
;; A real warehouse splits storage into a forward PICK FACE (compact, fast picking,
;; small max-on-hand) and BULK/reserve (deep storage). When a forward slot's on-hand
;; falls below its min, a replenishment move tops it back up from a bulk source of the
;; same SKU. This module:
;;   * `needs-replenish` — find the forward slots below their min (the ones to top up);
;;   * `replenish-plan`  — pair each needy slot with a matching-SKU bulk source and compute
;;     a refill qty = min(max-qty, bulk-available), rounded DOWN to a case-pack quantum
;;     (you cannot move a fractional case);
;;   * a no-phantom-replenishment gate (G: never plan stock that does not exist) — a hard
;;     stockout (the SKU is absent from ALL bulk sources) RAISES rather than planning a move
;;     against inventory that isn't there, matching the actor's raising-gate discipline
;;     (slotting G7 / picking G9 / handoff G10).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.replenish)

;; ── needs-replenish ──────────────────────────────────────────────────────────
;; A forward pick-face slot: {:slot-id :sku-id :qty :min :max}.
(defn needs-replenish
  "Forward slots whose on-hand `:qty` has dropped below `:min` (need topping up).
   Returns the needy slots in input order."
  [slots]
  (filter #(< (:qty %) (:min %)) slots))

;; ── replenish-plan ─────────────────────────────────────────────────────────────
(defn- round-down-to
  "Largest multiple of `quantum` that is ≤ `n` (a fractional case can't be moved).
   quantum ≤ 0 degenerates to a quantum of 1."
  [n quantum]
  (let [q (if (pos? quantum) quantum 1)]
    (* q (quot n q))))

(defn replenish-plan
  "For each forward slot below min (`needs-replenish`), pair it with a bulk source of
   the matching SKU and compute the refill quantity.

   `bulk` = a seq of sources {:bulk-id :sku-id :qty}. `opts` = {:case-pack n} (default 1).

   refill = round-down-to-case-pack( min(:max - :qty, bulk-available) )

   Returns plan entries {:slot-id :from-bulk :qty}. Zero-refill entries (no whole case
   movable, e.g. bulk < a single case-pack) are dropped — a plan never carries a no-op.

   G — no phantom replenishment: a slot whose SKU is in NO bulk source (a hard stockout)
   RAISES via ex-info, never planning a move against stock that doesn't exist."
  ([slots bulk] (replenish-plan slots bulk {}))
  ([slots bulk {:keys [case-pack] :or {case-pack 1}}]
   ;; total available per SKU across all bulk sources, + the first source id per SKU.
   (let [by-sku (group-by :sku-id bulk)]
     (->> (needs-replenish slots)
          (keep
           (fn [{:keys [slot-id sku-id qty max]}]
             (let [sources (get by-sku sku-id)]
               (when (empty? sources)
                 (throw (ex-info "hard stockout: SKU absent from all bulk (no phantom replenishment)"
                                 {:slot-id slot-id :sku-id sku-id})))
               (let [available (reduce + (map :qty sources))
                     want      (- max qty)
                     refill    (round-down-to (min want available) case-pack)]
                 (when (pos? refill)
                   {:slot-id slot-id :from-bulk (:bulk-id (first sources)) :qty refill})))))
          vec))))

;; ── demo ───────────────────────────────────────────────────────────────────────
(defn -main [& _]
  (let [slots [{:slot-id "f-g1" :sku-id "sku-fast" :qty 4  :min 10 :max 40}   ; below min → refill
               {:slot-id "f-g2" :sku-id "sku-med"  :qty 18 :min 12 :max 36}   ; healthy → skip
               {:slot-id "f-r1" :sku-id "sku-cold" :qty 2  :min 8  :max 24}]  ; below min → refill
        bulk  [{:bulk-id "blk-A" :sku-id "sku-fast" :qty 200}
               {:bulk-id "blk-B" :sku-id "sku-cold" :qty 13}]
        plan  (replenish-plan slots bulk {:case-pack 6})]
    (println "kuramori 倉守 — forward-pick replenishment (from bulk, case-pack quantum)")
    (println (format "needy forward slots: %d / %d" (count (needs-replenish slots)) (count slots)))
    (doseq [{:keys [slot-id from-bulk qty]} plan]
      (println (format "  replenish %-6s ← %-6s  qty %d (case-pack-rounded)" slot-id from-bulk qty)))
    (flush)))
