;; kuramori 倉守 — cross-actor handoff edges in the kotoba Datom log.
;;
;; The warehouse is a SEAM in the logistics chain, not an island:
;;     niyaku 荷役 (quay discharge) → kuramori 倉守 (warehouse) → todoke 届け (last-mile)
;; This module makes that chain queryable as append-only `:handoff/*` 縁 — the R0→R1
;; integration step that lets one actor's output become the next actor's input on the
;; shared Datom log, with source provenance on every edge.
;;
;;   * inbound  — a niyaku discharge record (a box+SKU landed on the apron) becomes a
;;                kuramori putaway intent;
;;   * outbound — a completed kuramori picked order becomes a todoke delivery intent;
;;   * a provenance gate (G10) — every handoff must name both a source and a
;;     destination actor; an orphan handoff RAISES (no unattributed chain edge).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0→R1 integration).
(ns kuramori.methods.handoff
  (:require [clojure.string :as str]))

(defn assert-handoff!
  "RAISE if a handoff lacks a source or destination actor (no unattributed chain
   edge — provenance is structural, G10)."
  [{:keys [from-actor to-actor] :as h}]
  (when (or (str/blank? (str from-actor)) (str/blank? (str to-actor)))
    (throw (ex-info "handoff missing source/destination actor" {:handoff h})))
  h)

(defn inbound-handoff
  "Map niyaku discharge records → kuramori putaway-intent handoffs.
   Each discharge: {:box-id :sku-id :weight-kg :temp}. Source actor = niyaku."
  [discharges]
  (mapv (fn [d]
          (assert-handoff!
           {:id (str "in." (:box-id d))
            :from-actor "niyaku" :to-actor "kuramori" :kind :inbound
            :payload {:sku-id (:sku-id d) :weight-kg (:weight-kg d) :temp (:temp d)}}))
        discharges))

(defn outbound-handoff
  "Map a completed kuramori picked order → a todoke last-mile delivery intent.
   `order` = {:id :picks [..]}. Destination actor = todoke."
  [order]
  (assert-handoff!
   {:id (str "out." (:id order))
    :from-actor "kuramori" :to-actor "todoke" :kind :outbound
    :payload {:order-id (:id order) :parcel-count (count (:picks order))}}))

(defn- fmt [v]
  (cond (keyword? v) (str v)
        (string? v) (if (str/starts-with? v ":") v (str \" v \"))
        :else (str v)))

(defn emit
  "EAVT handoff datoms (GROUND :add). Each handoff is an edge entity
   en.handoff.<from>.<to>.<id> carrying actor provenance + payload kind."
  [handoffs tx]
  (let [lines
        (mapcat
         (fn [h]
           (let [eid (str "en.handoff." (:from-actor h) "." (:to-actor h) "." (:id h))]
             [(str "[" (fmt eid) " :handoff/from-actor " (fmt (:from-actor h)) " " tx " :add]")
              (str "[" (fmt eid) " :handoff/to-actor " (fmt (:to-actor h)) " " tx " :add]")
              (str "[" (fmt eid) " :handoff/kind " (fmt (:kind h)) " " tx " :add]")]))
         handoffs)]
    (str ";; kuramori 倉守 — GENERATED handoff 縁 (chain: niyaku→kuramori→todoke). ADR-2606142000.\n"
         "[\n" (str/join "\n" lines) "\n]\n")))

(defn -main [& _]
  (let [discharges [{:box-id "b1" :sku-id "sku-fast" :weight-kg 8 :temp :ambient}
                    {:box-id "b2" :sku-id "sku-cold" :weight-kg 9 :temp :reefer}]
        in (inbound-handoff discharges)
        out (outbound-handoff {:id "ord-1" :picks ["s-g1" "s-r1" "s-b1"]})
        all (conj in out)]
    (print (emit all 1))
    (flush)))
