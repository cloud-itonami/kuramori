# ADR-0001 — kuramori gets a governed actor layer; G1–G8 stop being prose

- **Status**: accepted
- **Date**: 2026-08-06
- **Supersedes**: nothing. Extends ADR-2606142000 (kuramori R0).

## Context

kuramori had real substance and no containment.

The `kuramori.methods.*` tree is a genuine capability library: trapezoidal
AGV/AMR travel time, one-way segment-conflict detection, LPT makespan dispatch,
battery state-of-charge with an opportunity-charge gate, ABC velocity slotting,
putaway feasibility (weight / temperature class / hazmat segregation),
nearest-neighbour pick routing, multi-order batch consolidation — 45 tests and
169 assertions, all green, authored directly in Clojure rather than ported.

What it did **not** have was any of the governed-actor discipline every other
`cloud-itonami` vertical runs on. There was no `governor`, no `phase`, no
`store`, no `operation` graph, no `facts` catalog. The eight constitutional
gates (G1 design+sim only, G2 electric-only + charge gate, G3 no worker
surveillance, G4 dividend-coupled, G5 shared-zone speed cap, G6 Murakumo-only,
G7 hazmat segregation, G8 tazuna-teleoperable) existed **only as prose in
`CLAUDE.md`**. Two of them (G5, G7) happened to be enforced inside the methods
themselves; the other six were enforced by nobody.

The fleet maturity scan (ADR-2608052000) read this correctly and harshly:
`:repo/kind "corpus"`, `M_own` 0.120 against a fleet mean `M_eff` of 0.307,
with `substrate` / `test` / `governed` all at **0**. The zeros for substrate and
test were a layout artefact — the scanner measures `src/**` and `test/**`, and
kuramori's code lived at `kuramori/methods/**`. The zero for `governed` was not
an artefact. It was true.

## Decision

**1. Adopt the standard layout.** `kuramori/` → `src/kuramori/`, the test suite
→ `test/kuramori/methods_test.cljk`, and a `deps.edn` with the usual
`:dev` / `:run` / `:test` / `:lint` aliases. `run_tests.clj` is deleted: `bb` is
retired as this workspace's script host (ADR-2607173000), and a suite that
invokes itself on load cannot be composed with sibling suites.

**2. Add the actor layer**, following the shape `cloud-itonami-isic-5210`
established:

| namespace | role |
|---|---|
| `kuramori.facts` | per-jurisdiction spec-basis catalog (7 jurisdictions) |
| `kuramori.warehouseadvisor` | the sealed advisor — proposals only, never commits |
| `kuramori.governor` | the independent censor — 10 HARD checks + 2 double-actuation guards |
| `kuramori.phase` | 0→3 rollout gate; actuation is never auto-eligible |
| `kuramori.store` | `Store` protocol + `MemStore`, append-only ledger |
| `kuramori.operation` | langgraph StateGraph: intake → advise → govern → decide → commit \| hold \| approve |
| `kuramori.sim` | demo driver that walks every refusal |

**3. The governor re-derives no physics.** It calls
`agv-amr/effective-vmax`, `agv-amr/needs-charge?`, `agv-amr/find-conflicts` and
`slotting/putaway-feasible?` directly. A governor that reimplements the
arithmetic it is checking is checking its own copy, not the plan that will run.
This is the same discipline `terminal.governor` applies to `terminal.registry`.

**4. Each gate becomes a named, refusable rule.** G1 → `:actuation-not-authorised`,
G2 → `:battery-reserve`, G3 → `:worker-surveillance`, G4 → `:dividend-uncoupled`,
G5 → `:shared-zone-speed`, G7 → `:putaway-infeasible`, plus `:no-spec-basis`,
`:evidence-incomplete`, `:segment-conflict`, `:handoff-malformed`, and the
`:already-dispatched` / `:already-putaway-committed` guards.

G6 (Murakumo-only inference) and G8 (tazuna teleop) are **not** enforced here
and are not claimed to be. Both are platform-layer concerns: G6 binds whichever
`langchain.model` an `llm-advisor` is constructed with, G8 binds a teleop
substrate this repo does not contain. Naming them as governor rules would be
theatre.

**5. Two independent layers agree that actuation is a human call.**
`:fleet/dispatch` and `:putaway/commit` are absent from every phase's `:auto`
set including phase 3, *and* they are members of the governor's high-stakes set.
Neither layer is load-bearing alone.

## Verification

- **83 tests / 352 assertions, 0 failures** (`kbb -M:dev:test`), up from
  45/169. Pre-existing suite untouched and still green.
- **The demo actually refuses.** `kbb -M:dev:run` produces nine
  `:governor-hold` ledger entries, each isolating one rule:
  `:no-spec-basis` · `:shared-zone-speed` · `:segment-conflict` ·
  `:worker-surveillance` · `:putaway-infeasible` · `:actuation-not-authorised` ·
  `:dividend-uncoupled` · `:battery-reserve` · `:already-dispatched`.
  Two acts commit, both carrying `:approved-by`.
- **Mutation-tested.** Eight independent breaks — disabling each of the G1/G2/
  G3/G5/G7 checks, adding `:fleet/dispatch` to phase 3's `:auto` set, making an
  unknown jurisdiction satisfy its evidence requirement, and making the ledger
  overwrite instead of append — each turn the suite red (1–5 failures), and it
  returns green on revert. A gate that cannot fail is not a gate.
- **Every URL in `kuramori.facts` was fetched and returned 2xx on 2026-08-06.**

## Honest limits

- **ISO 3691-4 and ANSI/A3 R15.08 carry no URL.** They are the standards that
  actually specify this equipment class, but iso.org and automate.org refuse
  automated retrieval (HTTP 403). They are named in `:technical-standards` as
  prose and cited nowhere as links. `facts/coverage` reports this explicitly
  rather than letting the omission look like completeness.
- **`:provenance` claims reachability, not extraction.** No clause numbers are
  asserted from any instrument; the catalog records the publisher page and the
  instrument's identity, which is what the governor's spec-basis check needs.
- **The actor layer is JVM-only in practice.** The new namespaces are `.cljc`,
  but they require `kuramori.methods.*`, which are `.clj` and use
  `Double/MAX_VALUE`. Porting the capability library to `.cljc` is a separate,
  scoped change — not smuggled in here.
- **`floor-2`'s demo scenario holds with two rules, not one.** Its jurisdiction
  is unknown, so its commissioning assessment is itself refused, so evidence can
  never be on file. The pair is structurally inseparable and is documented as
  such in `kuramori.sim` rather than hidden by a contrived fixture.
- **This is still R0.** Nothing here touches a network or a device. The
  "dispatch" that commits is a plan record in the SSoT. `:actuation-authorised?`
  gates the transition to R1; it is a fact on a floor, not a capability grant,
  and a real R1 needs a real one.
