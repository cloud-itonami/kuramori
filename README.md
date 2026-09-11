# kuramori 倉守

**Warehouse intralogistics robotics — AGV/AMR transport + slotting + putaway/picking,
run as a governed actor.**
ADR-2606142000 · docs/adr/0001 · 🟡 R0 (design + sim) · Clojure-first.

The name does not say what it does, so: **kuramori (倉守, "warehouse-keeper") is the
warehouse floor** between **niyaku 荷役** (port quay↔yard) and last-mile delivery. A
free-roaming **AMR** + fixed-guidepath **AGV** fleet doing ABC velocity-based slotting,
hazmat-segregated putaway, pick-routing, and makespan-balanced dispatch — with a sealed
advisor behind an independent Governor that can refuse it.

It closes the warehouse-handling GAP named in ADR-2606073001 §3 (積み下ろし was only
*partial*).

## Run

```bash
kbb -M:dev:test         # 83 tests / 352 assertions
kbb -M:dev:run          # the governed actor demo — walks every refusal
kbb -M:analyze          # end-to-end R0 planning report (capability library only)
kbb -M:datom-emit       # kotoba EAVT Datom log
```

## Two layers

**The capability library** (`src/kuramori/methods/**`) is pure, dependency-free Clojure.
It was written and test-covered first, and it owns all the physics.

| Method | Role |
|---|---|
| `agv_amr.clj`    | trapezoidal travel-time · AGV segment-conflict · AMR shared-zone yield (G5) · battery opportunity-charge gate (G2) · LPT makespan dispatch |
| `slotting.clj`   | ABC velocity classing · golden-zone slot assignment · putaway feasibility (weight/temp/hazmat — raises, G7) · nearest-neighbour pick-route |
| `picking.clj`    | multi-order batch consolidation (FFD wave packing under tote-cart capacity — atomic-order raise, G9) · concurrent zone-occupancy congestion detection · overflow stagger |
| `handoff.clj`    | cross-actor chain edges in the Datom log — inbound putaway + outbound delivery · provenance gate (orphan handoff raises, G10) · `:handoff/*` EAVT 縁 |
| `analyze.clj`    | end-to-end: load seed → slot → pick-route → dispatch → battery gate → report |
| `datom_emit.clj` | kotoba EAVT projection (`:wh.*` GROUND + `:bond/*` DERIVED transient) |

**The actor layer** (`src/kuramori/*.cljc`) contains the intelligence and can refuse it.

| Namespace | Role |
|---|---|
| `facts`             | per-jurisdiction spec-basis catalog (JPN/USA/EUR/GBR/CAN/KOR/AUS) |
| `warehouseadvisor`  | the sealed advisor — returns *proposals*, never commits |
| `governor`          | the independent censor — 10 HARD rules + 2 double-actuation guards |
| `phase`             | 0→3 rollout gate; actuation is never auto-eligible at any phase |
| `store`             | `Store` protocol + `MemStore`, append-only ledger |
| `operation`         | langgraph StateGraph: intake → advise → govern → decide → commit \| hold \| approve |
| `sim`               | demo driver that walks every refusal |

The governor **re-derives no physics** — it calls `agv-amr/effective-vmax`,
`agv-amr/needs-charge?`, `agv-amr/find-conflicts` and `slotting/putaway-feasible?`
directly. A governor that reimplements what it checks is checking its own copy.

## Actuation

Two ops touch the real world: `:fleet/dispatch` (robots move on a floor humans walk) and
`:putaway/commit` (the WMS book-of-record downstream picking trusts). **Neither
auto-commits at any phase, including phase 3.** The governor's high-stakes set and the
phase `:auto` sets enforce this independently — two layers, not one.

## Gates

| Gate | Enforced as |
|---|---|
| G1 design+sim only (no-server-key) | `:actuation-not-authorised` |
| G2 electric-only + charge gate     | `:battery-reserve` |
| G3 no worker surveillance          | `:worker-surveillance` |
| G4 Displacement-Dividend-coupled   | `:dividend-uncoupled` |
| G5 shared-zone speed cap           | `:shared-zone-speed` |
| G7 hazmat segregation              | `:putaway-infeasible` |
| G6 Murakumo-only inference         | **not enforced here** — binds the `langchain.model` an `llm-advisor` is built with |
| G8 tazuna-teleoperable             | **not enforced here** — the teleop substrate is not in this repo |

Plus `:no-spec-basis`, `:evidence-incomplete`, `:segment-conflict`,
`:handoff-malformed`, and the `:already-dispatched` / `:already-putaway-committed`
guards. Full text of the gates in `CLAUDE.md`; the reasoning in `docs/adr/0001`.

Apache 2.0 + etzhayyim Charter Compliance Rider v3.1.
