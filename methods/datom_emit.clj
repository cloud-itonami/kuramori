;; kuramori 倉守 — kotoba Datom-log emitter (canonical EAVT state, ADR-2605312345).
;;
;; Projects the warehouse graph into append-only kotoba Datoms [e a v tx op].
;;   GROUND (op :add, durable) — zone / slot / sku / robot node datoms + placement 縁.
;;     This IS the Datom log.
;;   DERIVED (op :derived, transient :bond/is-transient true) — slotting weighted-travel,
;;     dispatch makespan, battery gate; computed on READ, NOT persisted (N1/G2 pattern,
;;     mirrors asobi/datom_emit).
;;
;; Pure Clojure, no deps → babashka-runnable AND kotoba-pywasm-portable.
;; Per ADR-2606142000 (kuramori R0).
(ns kuramori.methods.datom-emit
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kuramori.methods.analyze :as az]))

(defn fmt
  "Format a value as an EDN Datom field: keywords bare, strings quoted, bools/nil literal."
  [v]
  (cond
    (true? v) "true"
    (false? v) "false"
    (nil? v) "nil"
    (keyword? v) (str v)
    (string? v) (if (str/starts-with? v ":")
                  v
                  (str \" (str/escape v {\\ "\\\\" \" "\\\""}) \"))
    (float? v) (format "%g" v)
    :else (str v)))

(defn datom [e a v tx op]
  (str "[" (fmt e) " " (str a) " " (fmt v) " " tx " " (str op) "]"))

(defn- base-lines
  "Inner GROUND + DERIVED datom lines for the base `run` result (no header/brackets)."
  [seed res tx]
  (concat
   ;; GROUND — zones
   (for [z (:zones seed)] (datom (:id z) :wh.zone/kind (:kind z) tx :add))
   ;; GROUND — slots (+ slot-in-zone 縁)
   (mapcat (fn [s] [(datom (:id s) :wh.slot/dist-from-face (double (:dist-from-face s 0)) tx :add)
                    (datom (:id s) :wh.slot/max-kg (:max-kg s 0) tx :add)
                    (datom (:id s) :wh.slot/in-zone (:zone s) tx :add)])
           (:slots seed))
   ;; GROUND — SKUs (+ ABC class)
   (mapcat (fn [k] [(datom (:id k) :wh.sku/velocity (:velocity k 0) tx :add)
                    (datom (:id k) :wh.sku/weight-kg (:weight-kg k 0) tx :add)
                    (datom (:id k) :wh.sku/abc (get-in res [:abc (:id k)]) tx :add)])
           (:skus seed))
   ;; GROUND — robots (fleet)
   (for [r (:fleet seed)] (datom (:id r) :wh.robot/kind (:kind r) tx :add))
   ;; GROUND — placement 縁 (sku → slot)
   (mapcat (fn [[sku-id slot-id]]
             (let [e (str "en." sku-id ".slotted-in." slot-id)]
               [(datom e :en/from sku-id tx :add)
                (datom e :en/to slot-id tx :add)
                (datom e :en/kind :slotted-in tx :add)]))
           (get-in res [:slotting :placement]))
   ;; DERIVED — transient readouts (computed on read; not durable)
   [";; ── DERIVED readouts (transient; computed on read) ──"
    (datom (:id (:facility seed)) :bond/weighted-travel
           (double (get-in res [:slotting :weighted-travel])) tx :derived)
    (datom (:id (:facility seed)) :bond/dispatch-makespan
           (double (get-in res [:dispatch :makespan])) tx :derived)
    (datom (:id (:facility seed)) :bond/charge-needed
           (get-in res [:battery :charge-needed]) tx :derived)]))

(defn- day-lines
  "Inner datom lines for the FULL run-day pipeline artifacts — so the canonical log
   captures the whole day (inbound/outbound handoffs, replenish moves, returns
   dispositions) not just slotting. GROUND :add for operations; DERIVED for metrics."
  [res tx]
  (let [day (:day res)]
    (concat
     [";; ── run-day operations (GROUND) ──"]
     ;; inbound + outbound handoff 縁
     (mapcat (fn [h]
               (let [e (str "en.handoff." (:from-actor h) "." (:to-actor h) "." (:id h))]
                 [(datom e :handoff/from-actor (:from-actor h) tx :add)
                  (datom e :handoff/to-actor (:to-actor h) tx :add)
                  (datom e :handoff/kind (:kind h) tx :add)]))
             (concat (:inbound day) (when-let [o (:outbound day)] [o])))
     ;; replenish moves
     (for [m (:replenish day)]
       (datom (str "rep." (:slot-id m)) :wh.replenish/qty (:qty m) tx :add))
     ;; returns dispositions (bucket values are item maps → project their :id)
     (mapcat (fn [[disp items]]
               (for [it items]
                 (datom (if (map? it) (:id it) it) :wh.return/disposition disp tx :add)))
             (select-keys (:returns day) [:restock :refurbish :scrap]))
     ;; DERIVED day metrics
     [";; ── run-day metrics (DERIVED) ──"]
     (when-let [cyc (:cyclecount day)]
       [(datom "facility" :bond/cyclecount-accuracy (double (:accuracy cyc)) tx :derived)])
     (when-let [c (:cartons day)]
       [(datom "facility" :bond/carton-count (:count c) tx :derived)])
     [(datom "facility" :bond/pick-waves (count (:waves day)) tx :derived)])))

(defn- wrap [lines]
  (str ";; kuramori 倉守 — GENERATED kotoba Datom log (ADR-2606142000). DO NOT hand-edit.\n"
       ";; Canonical EAVT state (ADR-2605312345). [e a v tx op].\n"
       ";; GROUND op :add = durable. DERIVED :bond/is-transient = computed on read (N1/G2).\n"
       "[\n" (str/join "\n" (remove nil? lines)) "\n]\n"))

(defn emit
  "Emit the base warehouse Datom log (slotting/dispatch) as an EDN string.
   `res` = an analyze/run result. Output is unchanged from the R0 emitter."
  [seed res tx]
  (wrap (base-lines seed res tx)))

(defn emit-day
  "Emit the FULL day Datom log — base GROUND/DERIVED PLUS the run-day pipeline
   artifacts (handoffs, replenish, returns, cycle-count). `day-res` = az/run-day."
  [seed day-res tx]
  (wrap (concat (base-lines seed day-res tx) (day-lines day-res tx))))

(defn -main [& args]
  (let [path (or (first args) "20-actors/kuramori/data/warehouse.edn")
        seed (az/load-seed path)
        day-res (az/run-day seed)]
    (print (emit-day seed day-res 1))
    (flush)))
