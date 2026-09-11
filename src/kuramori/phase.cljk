(ns kuramori.phase
  "Phase 0->3 staged rollout for the kuramori warehouse-robotics actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-register  -- floor registration allowed, every
                                   write needs human approval.
    Phase 2  assisted-plan      -- adds the commissioning assessment and
                                   slotting PLANNING writes (compute +
                                   store a plan), still approval.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                   `:floor/register` and
                                   `:slotting/plan` (both pure planning,
                                   no robot moves) may auto-commit.
                                   `:fleet/dispatch`/`:putaway/commit`
                                   NEVER auto-commit, at any phase.

  `:fleet/dispatch`/`:putaway/commit` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural fact,
  not a rollout milestone still to come. Releasing a dispatch plan puts
  robots in motion on a floor humans walk (CLAUDE.md G1); committing a
  putaway writes the WMS book-of-record downstream picking trusts. Both
  are always a human warehouse supervisor's call.
  `kuramori.governor`'s actuation high-stakes gate enforces the same
  invariant independently -- two layers, not one, agree on this.

  Note this domain's phase-3 `:auto` set has TWO members, not the usual
  one: `:floor/register` and `:slotting/plan` are both pure planning
  compute that moves nothing (G1 R0 is exactly this), so there is no
  honest reason to hold the second behind a human when the first is
  released.")

(def read-ops #{})
(def write-ops #{:floor/register :commissioning/assess :slotting/plan
                 :fleet/dispatch :putaway/commit})

;; NOTE the invariant: `:fleet/dispatch`/`:putaway/commit` are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"         :writes #{}                 :auto #{}}
   1 {:label "assisted-register" :writes #{:floor/register}  :auto #{}}
   2 {:label "assisted-plan"
      :writes #{:floor/register :commissioning/assess :slotting/plan}
      :auto #{}}
   3 {:label "supervised-auto"   :writes write-ops
      :auto #{:floor/register :slotting/plan}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:fleet/dispatch`/`:putaway/commit` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or hold
    if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Warehouse Robotics Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
