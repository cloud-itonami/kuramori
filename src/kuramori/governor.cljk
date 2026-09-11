(ns kuramori.governor
  "Warehouse Robotics Governor -- the independent compliance layer that
  earns the WarehouseAdvisor the right to commit.

  The advisor is a planner. It has no notion of whether this floor is
  actually authorised for real actuation, whether the jurisdiction's
  commissioning evidence is on file, whether the plan it just produced
  would drive a robot faster than the shared-zone cap allows, whether it
  would strand a vehicle below its battery reserve, whether the putaway
  it proposes is physically and lawfully feasible, or whether two AGVs
  would occupy the same one-way segment at the same time. So this MUST
  be a separate system able to *reject* a proposal and fall back to HOLD.

  WHERE THE PHYSICS COMES FROM. kuramori's capability library is its own
  `kuramori.methods.*` tree, authored and test-covered (45 tests / 169
  assertions) BEFORE this actor layer existed. The governor therefore
  re-derives nothing: it calls `agv-amr/effective-vmax`,
  `agv-amr/needs-charge?`, `agv-amr/find-conflicts` and
  `slotting/putaway-feasible?` directly. This is the same 'reuse the
  capability library's own validated function' discipline
  `terminal.governor` (isic-5210) applies to `terminal.registry` -- a
  governor that re-implements the physics it is checking is checking its
  own copy, not the plan that will actually run.

  The constitutional gates in `CLAUDE.md` (G1-G8) were prose until this
  namespace existed. Each HARD check below names the gate it enforces.

  HARD violations -- a human approver CANNOT override them:

    1. `:no-spec-basis`            -- did the proposal cite an OFFICIAL
                                      source (`kuramori.facts`), or
                                      invent one?
    2. `:evidence-incomplete`      -- for an actuation op, is the
                                      jurisdiction's commissioning
                                      evidence (risk assessment /
                                      protective-stop verification /
                                      operator competency) actually on
                                      file?
    3. `:actuation-not-authorised` -- G1. R0 is design + sim only. A
                                      `:fleet/dispatch` against a floor
                                      not explicitly marked
                                      `:actuation-authorised?` is
                                      refused. This is the no-server-key
                                      gate, enforced in code rather than
                                      by the absence of a key.
    4. `:shared-zone-speed`        -- G5. INDEPENDENTLY recompute
                                      `agv-amr/effective-vmax` for every
                                      shared-zone leg and refuse any plan
                                      whose commanded speed exceeds the
                                      cap. The cap is not tunable up by a
                                      planner; a planner that says it is
                                      gets held here.
    5. `:battery-reserve`          -- G2. INDEPENDENTLY call
                                      `agv-amr/needs-charge?`; a dispatch
                                      that would drop a vehicle below its
                                      reserve floor must route to a
                                      charger first, never be released.
    6. `:segment-conflict`         -- INDEPENDENTLY call
                                      `agv-amr/find-conflicts`; a plan
                                      reserving the same one-way segment
                                      for two vehicles at overlapping
                                      times is refused.
    7. `:putaway-infeasible`       -- G7. INDEPENDENTLY call
                                      `slotting/putaway-feasible?` for
                                      every proposed placement; weight /
                                      temperature class / hazmat
                                      segregation violations surface,
                                      never get silently forced.
    8. `:worker-surveillance`      -- G3. KPI is equipment throughput,
                                      never a per-worker pace ranking or
                                      biometric. A proposal carrying
                                      per-worker metrics is refused
                                      outright.
    9. `:dividend-uncoupled`       -- G4. An actuation op against a floor
                                      whose Displacement-Dividend cohort
                                      is not funded is refused.
   10. `:handoff-malformed`        -- a PRESENT-but-malformed cross-actor
                                      `:handoff` record. Its ABSENCE is
                                      never itself a violation.

  Plus two double-actuation guards (`:already-dispatched` /
  `:already-putaway-committed`) which need no upstream comparison, off
  dedicated booleans rather than a `:status` value.

  The confidence/actuation gate is SOFT: it asks a human to look. But
  see `kuramori.phase` -- for `:fleet/dispatch`/`:putaway/commit` NO
  phase ever allows auto-commit either. Two independent layers agree
  that actuation is always a human call."
  (:require [kuramori.facts :as facts]
            [kuramori.store :as store]
            [kuramori.methods.agv-amr :as agv]
            [kuramori.methods.slotting :as slotting]))

(def confidence-floor 0.6)

(def actuation-ops
  "Ops that touch the real world. Releasing a dispatch plan puts robots
  in motion on a floor humans walk; committing a putaway writes the WMS
  book-of-record that downstream picking trusts."
  #{:fleet/dispatch :putaway/commit})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean."
  actuation-ops)

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's mobile-robot safety requirements."
  [{:keys [op subject]} proposal st]
  (when (contains? (conj actuation-ops :slotting/plan :commissioning/assess) op)
    (let [f (store/floor st subject)
          value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value)))
                (not (facts/spec-basis-known? (:jurisdiction f))))
        [{:rule :no-spec-basis
          :detail (str "公式spec-basisの引用が無い、または法域 "
                       (pr-str (:jurisdiction f))
                       " が kuramori.facts に無い -- 安全要件を発明しない")}]))))

(defn- evidence-incomplete-violations
  "For an actuation op, the jurisdiction's commissioning evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (contains? actuation-ops op)
    (let [f (store/floor st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction f) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要証跡(リスクアセスメント/保護停止検証/操作者力量記録)が充足していない"}]))))

(defn- actuation-not-authorised-violations
  "G1 -- R0 is design + sim only. Real actuation needs an explicit
  per-floor authorisation fact; its absence is a refusal, not a default."
  [{:keys [op subject]} st]
  (when (contains? actuation-ops op)
    (let [f (store/floor st subject)]
      (when-not (true? (:actuation-authorised? f))
        [{:rule :actuation-not-authorised
          :detail (str subject " は実動作認可(:actuation-authorised?)が無い -- "
                       "G1 設計+シミュレーションのみ。認可なき実機解放は拒否する")}]))))

(defn- shared-zone-speed-violations
  "G5 -- INDEPENDENTLY recompute the shared-zone speed cap from the
  capability library and refuse any leg commanded above it. `:legs` is
  a seq of {:zone .. :shared? bool :commanded-vmax m/s}."
  [{:keys [op subject]} proposal st]
  (when (contains? actuation-ops op)
    (let [f (store/floor st subject)
          vehicle (:vehicle f)
          legs (get-in proposal [:value :legs])
          bad (when (and vehicle (seq legs))
                (filter (fn [{:keys [shared? commanded-vmax]}]
                          (and (some? commanded-vmax)
                               (> commanded-vmax
                                  (agv/effective-vmax vehicle (boolean shared?)))))
                        legs))]
      (when (seq bad)
        [{:rule :shared-zone-speed
          :detail (str "人共存ゾーンの速度上限 " agv/shared-zone-cap-mps
                       " m/s を超える指令が " (count bad) " 区間ある -- "
                       "G5 の上限はプランナが引き上げられない")}]))))

(defn- battery-reserve-violations
  "G2 -- INDEPENDENTLY call `needs-charge?`. A dispatch that would strand
  a vehicle below its reserve floor must route to a charger first."
  [{:keys [op subject]} proposal st]
  (when (= op :fleet/dispatch)
    (let [f (store/floor st subject)
          vehicle (:vehicle f)
          total-m (get-in proposal [:value :total-distance-m])]
      (when (and vehicle (number? total-m)
                 (agv/needs-charge? vehicle total-m))
        [{:rule :battery-reserve
          :detail (str subject " の計画走行 " total-m " m は車両を予備SoC下限 "
                       (:soc-min vehicle) " 未満に落とす -- "
                       "G2 機会充電を先に経由せずに解放できない")}]))))

(defn- segment-conflict-violations
  "INDEPENDENTLY call `find-conflicts`. A plan reserving the same one-way
  segment for two vehicles at overlapping times is refused."
  [{:keys [op]} proposal]
  (when (= op :fleet/dispatch)
    (let [reservations (get-in proposal [:value :reservations])
          conflicts (when (seq reservations) (agv/find-conflicts reservations))]
      (when (seq conflicts)
        [{:rule :segment-conflict
          :detail (str "一方通行セグメントの時間重複が " (count conflicts)
                       " 組ある -- 衝突する配車計画は解放できない")}]))))

(defn- putaway-infeasible-violations
  "G7 -- INDEPENDENTLY call `putaway-feasible?` for every proposed
  placement. Weight / temperature / hazmat violations surface."
  [{:keys [op]} proposal]
  (when (= op :putaway/commit)
    (let [placements (get-in proposal [:value :placements])
          bad (filter (fn [{:keys [sku slot]}]
                        (not (slotting/putaway-feasible? sku slot)))
                      placements)]
      (when (seq bad)
        [{:rule :putaway-infeasible
          :detail (str (count bad) " 件の格納が重量/温度帯/危険物分離のいずれかで不成立 -- "
                       "G7 不成立な格納は黙って押し込まない")}]))))

(defn- worker-surveillance-violations
  "G3 -- KPI is equipment throughput, never a per-worker pace ranking or
  biometric. A proposal carrying per-worker metrics is refused outright,
  at every op, including read-shaped ones."
  [_request proposal]
  (let [value (:value proposal)
        banned [:worker-metrics :picker-pace :biometric :per-worker-ranking
                :worker-productivity-ranking]
        present (filterv #(some? (get value %)) banned)]
    (when (seq present)
      [{:rule :worker-surveillance
        :detail (str "作業者個人の指標 " (pr-str present) " を含む提案 -- "
                     "G3 KPI は設備スループットであって個人のペースではない")}])))

(defn- dividend-uncoupled-violations
  "G4 -- an actuation op against a floor whose Displacement-Dividend
  cohort is not funded is refused."
  [{:keys [op subject]} st]
  (when (contains? actuation-ops op)
    (let [f (store/floor st subject)]
      (when-not (true? (:dividend-cohort-funded? f))
        [{:rule :dividend-uncoupled
          :detail (str subject " は置換配当コホートが未拠出 -- "
                       "G4 労働置換は配当と結合されない限り実行しない")}]))))

(defn- already-dispatched-violations
  [{:keys [op subject]} st]
  (when (= op :fleet/dispatch)
    (when (store/floor-already-dispatched? st subject)
      [{:rule :already-dispatched :detail (str subject " は既に配車解放済み")}])))

(defn- already-putaway-committed-violations
  [{:keys [op subject]} st]
  (when (= op :putaway/commit)
    (when (store/floor-already-putaway-committed? st subject)
      [{:rule :already-putaway-committed :detail (str subject " は既に格納計上済み")}])))

(defn- handoff-malformed-violations
  "HARD, but ONLY when a :handoff map is actually present -- its ABSENCE
  is never itself a violation."
  [{:keys [op]} proposal]
  (when (contains? actuation-ops op)
    (let [handoff (get-in proposal [:value :handoff])]
      (when (and (some? handoff) (not (facts/handoff-record-well-formed? handoff)))
        [{:rule :handoff-malformed
          :detail "添付された:handoffレコードが必須フィールドを欠く、または数量が正でない"}]))))

(defn check
  "Censors a WarehouseAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal st)
                           (evidence-incomplete-violations request st)
                           (actuation-not-authorised-violations request st)
                           (shared-zone-speed-violations request proposal st)
                           (battery-reserve-violations request proposal st)
                           (segment-conflict-violations request proposal)
                           (putaway-infeasible-violations request proposal)
                           (worker-surveillance-violations request proposal)
                           (dividend-uncoupled-violations request st)
                           (already-dispatched-violations request st)
                           (already-putaway-committed-violations request st)
                           (handoff-malformed-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
