(ns kuramori.warehouseadvisor
  "WarehouseAdvisor client -- the *contained intelligence node* for the
  kuramori warehouse-robotics actor.

  It normalizes floor registration, drafts the per-jurisdiction
  commissioning evidence checklist, drafts a slotting/dispatch plan, and
  drafts the two actuation actions (release a dispatch, commit a
  putaway). CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the facts it cited), never a committed
  record and never a released dispatch. Every output is censored
  downstream by `kuramori.governor` before anything touches the SSoT,
  and `:fleet/dispatch`/`:putaway/commit` proposals NEVER auto-commit at
  any phase -- see `kuramori.phase`.

  The planning itself is NOT invented here: `:slotting/plan` and
  `:fleet/dispatch` proposals are computed by the capability library
  (`kuramori.methods.slotting` / `kuramori.methods.agv-amr`), which the
  governor then independently re-checks. The advisor's job is to choose
  WHICH plan to propose and to cite its basis; the arithmetic is the
  library's.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline (G1: no network, no device). In
  production this calls a real LLM with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str          ; human-facing draft / finding
     :rationale  str          ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]  ; sources the advisor used -- SCANNED too
     :effect     kw           ; how a commit would mutate the SSoT
     :stake      kw|nil       ; :fleet/dispatch | :putaway/commit | nil
     :value      map          ; op payload -- SCANNED by the physical gates
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [kuramori.facts :as facts]
            [kuramori.store :as store]
            [kuramori.methods.agv-amr :as agv]
            [kuramori.methods.slotting :as slotting]
            [langchain.model :as model]))

(defn- cites-for
  "The source list for a floor's jurisdiction. EMPTY when the
  jurisdiction has no spec-basis -- the advisor must not invent one, and
  an empty `:cites` is exactly what makes the governor hold."
  [jurisdiction]
  (vec (:sources (facts/jurisdiction jurisdiction))))

(defn- register-floor [_st {:keys [subject value]}]
  {:summary (str subject " のフロア登録案")
   :rationale "ゾーン図・車両諸元・人共存ゾーンを正規化した(実動作は伴わない)"
   :cites (cites-for (:jurisdiction value))
   :effect :floor/upsert
   :stake nil
   :value (assoc value :id subject)
   :confidence 0.9})

(defn- assess-commissioning
  "Draft the jurisdiction's commissioning evidence checklist. The
  advisor may only report which items the operator has actually filed;
  it cannot invent the required set -- that comes from `kuramori.facts`."
  [st {:keys [subject value]}]
  (let [f (store/floor st subject)
        filed (set (:filed value))]
    {:summary (str subject " の commissioning 証跡評価")
     :rationale (str "法域 " (:jurisdiction f) " の必要証跡は "
                     (pr-str (facts/required-evidence (:jurisdiction f)))
                     "、提出済みは " (pr-str filed))
     :cites (cites-for (:jurisdiction f))
     :effect :commissioning-assessment/set
     :stake nil
     :value {:checklist filed}
     :confidence 0.85}))

(defn- plan-slotting
  "Compute an ABC slotting plan through the capability library."
  [st {:keys [subject value]}]
  (let [f (store/floor st subject)
        {:keys [skus slots abc-opts]} value
        plan (try (slotting/assign-slots skus slots (or abc-opts {}))
                  (catch #?(:clj Exception :cljs :default) e
                    {:error (ex-message e)}))]
    (if (:error plan)
      {:summary (str subject " の格納計画は成立しない")
       :rationale (str "slotting/assign-slots が拒否: " (:error plan))
       :cites (cites-for (:jurisdiction f))
       :effect :noop :stake nil :value {} :confidence 0.0}
      {:summary (str subject " のABC格納計画案")
       :rationale (str "capability library slotting/assign-slots による。加重移動距離 "
                       (:weighted-travel plan))
       :cites (cites-for (:jurisdiction f))
       :effect :plan/set
       :stake nil
       :value plan
       :confidence 0.85})))

(defn- propose-dispatch
  "Draft a fleet-dispatch release. The makespan/assignment come from the
  capability library; the governor re-checks speed, battery and segment
  conflicts independently."
  [st {:keys [subject value]}]
  (let [f (store/floor st subject)
        {:keys [moves vehicle-ids]} value
        vehicle (:vehicle f)
        plan (when (and (seq moves) (seq vehicle-ids) vehicle)
               (agv/dispatch moves vehicle-ids vehicle))]
    {:summary (str subject " の配車解放案 (makespan " (:makespan plan) " s)")
     :rationale "capability library agv-amr/dispatch (LPT) による割当。実機解放は人が判断する"
     :cites (cites-for (:jurisdiction f))
     :effect :floor/mark-dispatched
     :stake :fleet/dispatch
     :value (merge value plan)
     :confidence 0.8}))

(defn- propose-putaway
  "Draft a putaway commit to the WMS book-of-record."
  [st {:keys [subject value]}]
  (let [f (store/floor st subject)]
    {:summary (str subject " の格納計上案 (" (count (:placements value)) " 件)")
     :rationale "格納可否は slotting/putaway-feasible? が判定する。計上は人が判断する"
     :cites (cites-for (:jurisdiction f))
     :effect :floor/mark-putaway-committed
     :stake :putaway/commit
     :value value
     :confidence 0.8}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :floor/register       (register-floor db request)
    :commissioning/assess (assess-commissioning db request)
    :slotting/plan        (plan-slotting db request)
    :fleet/dispatch       (propose-dispatch db request)
    :putaway/commit       (propose-putaway db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :value {} :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは倉庫内物流ロボット(AGV/AMR)運用エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った出典のベクタ) "
       ":effect(:floor/upsert|:plan/set|:floor/mark-dispatched|:floor/mark-putaway-committed) "
       ":stake(:fleet/dispatch か :putaway/commit か nil) :value(操作ペイロード) "
       ":confidence(0..1)。\n"
       "重要: 登録されていない法域の安全要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "人共存ゾーンの速度上限・電池予備量・危険物分離を偽って報告してはいけません。"
       "作業者個人のペースや生体情報を指標に含めてはいけません。"))

(defn- facts-for [st {:keys [subject]}]
  {:floor (store/floor st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the governor escalates/holds -- an
  LLM hiccup can never auto-release a dispatch or auto-commit a putaway."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :value #(or % {}))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :value {} :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :warehouseadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
