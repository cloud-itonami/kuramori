;; kuramori 倉守 — reverse logistics (returns processing / disposition).
;;
;; A real warehouse runs the OUTBOUND flow in reverse too: a returned item arrives,
;; is graded on physical condition, and dispositioned to one of three downstream paths
;; rather than being blindly shoved back onto the pick face. This module:
;;   * `disposition` — map a returned item's condition grade (1..5) to a downstream path:
;;       grade ≥4 → :restock     (sellable as-is, back to the forward pick face)
;;       grade 2-3 → :refurbish  (recoverable, route to rework before re-stock)
;;       grade 1   → :scrap      (unrecoverable, route to hodoki/material recovery)
;;   * `process-returns` — fold a seq of returned items into the three buckets plus a
;;     restock-plan note (how many units flow back to the pick face);
;;   * a no-blind-restock gate — a returned item MISSING a condition grade RAISES rather
;;     than defaulting to restock; an ungraded return must surface, never be silently
;;     waved back onto the shelf (matches the actor's raising-gate discipline —
;;     slotting G7 / picking G9 / handoff G10 / replenish no-phantom).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.returns)

;; ── disposition ────────────────────────────────────────────────────────────────
;; A returned item: {:id :sku-id :condition-grade 1..5}.
(defn disposition
  "Map a returned item to its downstream disposition path by condition grade.
     grade ≥4 → :restock      grade 2-3 → :refurbish      grade 1 → :scrap
   No-blind-restock gate: an item with no `:condition-grade` RAISES — an ungraded
   return is never silently restocked (it must be inspected first)."
  [{:keys [id condition-grade] :as _item}]
  (when (nil? condition-grade)
    (throw (ex-info "returned item has no condition-grade (no blind restock)"
                    {:id id})))
  (cond (>= condition-grade 4) :restock
        (>= condition-grade 2) :refurbish
        :else                  :scrap))

;; ── process-returns ──────────────────────────────────────────────────────────────
(defn process-returns
  "Fold a seq of returned items into disposition buckets.
   Returns {:restock [...] :refurbish [...] :scrap [...] :restock-plan {...}} where
   :restock-plan = {:units <count back to pick face> :note <string>}.
   Calls `disposition` per item (so an ungraded item RAISES the whole run — G discipline)."
  [items]
  (let [buckets (reduce (fn [acc item]
                          (update acc (disposition item) (fnil conj []) item))
                        {:restock [] :refurbish [] :scrap []}
                        items)
        units   (count (:restock buckets))]
    (assoc buckets :restock-plan
           {:units units
            :note  (format "%d unit(s) restock to forward pick face; %d to refurbish; %d to scrap"
                           units
                           (count (:refurbish buckets))
                           (count (:scrap buckets)))})))

;; ── demo ───────────────────────────────────────────────────────────────────────
(defn -main [& _]
  (let [items [{:id "r1" :sku-id "sku-fast" :condition-grade 5}   ; → restock
               {:id "r2" :sku-id "sku-med"  :condition-grade 3}   ; → refurbish
               {:id "r3" :sku-id "sku-cold" :condition-grade 2}   ; → refurbish
               {:id "r4" :sku-id "sku-flam" :condition-grade 1}]  ; → scrap
        {:keys [restock refurbish scrap restock-plan]} (process-returns items)]
    (println "kuramori 倉守 — reverse logistics (returns disposition)")
    (println (format "  restock:   %d  %s" (count restock)   (mapv :id restock)))
    (println (format "  refurbish: %d  %s" (count refurbish) (mapv :id refurbish)))
    (println (format "  scrap:     %d  %s" (count scrap)      (mapv :id scrap)))
    (println (str "  restock-plan: " (:note restock-plan)))
    (flush)))
