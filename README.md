# kuramori 倉守

**Warehouse intralogistics robotics — AGV/AMR transport + slotting + putaway/picking.**
Tier-B actor · ADR-2606142000 · 🟡 R0 (design + sim) · Clojure-first.

kuramori closes the warehouse-handling GAP named in ADR-2606073001 §3 (積み下ろし was only
*partial*). It is the floor between **niyaku 荷役** (port quay↔yard) and **todoke 届け**
(last-mile): a free-roaming **AMR** + fixed-guidepath **AGV** fleet doing ABC velocity-based
slotting, hazmat-segregated putaway, pick-routing, and makespan-balanced dispatch.

It is the **reference Clojure-first actor** — the first whose methods are authored directly
in babashka-runnable Clojure (pure, no deps → also kotoba-pywasm-portable).

## Run

```bash
bb --classpath 20-actors 20-actors/kuramori/methods/test_kuramori.clj   # 15 tests / 43 assertions
bb --classpath 20-actors -m kuramori.methods.analyze                    # → warehouse R0 report
bb --classpath 20-actors -m kuramori.methods.datom-emit                 # → kotoba EAVT Datom log
```

## What it does

| Method | Role |
|---|---|
| `agv_amr.clj`    | trapezoidal travel-time · AGV segment-conflict · AMR shared-zone yield (G5) · battery opportunity-charge gate (G2) · LPT makespan dispatch |
| `slotting.clj`   | ABC velocity classing · golden-zone slot assignment · putaway feasibility (weight/temp/hazmat — raises, G7) · nearest-neighbour pick-route |
| `picking.clj`    | multi-order batch consolidation (FFD wave packing under tote-cart capacity — atomic-order raise, G9) · concurrent zone-occupancy congestion detection · overflow stagger |
| `handoff.clj`    | cross-actor chain edges in the Datom log — niyaku→kuramori inbound putaway + kuramori→todoke outbound delivery · provenance gate (orphan handoff raises, G10) · `:handoff/*` EAVT 縁 |
| `analyze.clj`    | end-to-end: load seed → slot → pick-route → dispatch → battery gate → report |
| `datom_emit.clj` | kotoba EAVT projection (`:wh.*` GROUND + `:bond/*` DERIVED transient) |

## Gates

R0 design+sim only (G1, no-server-key) · electric-only + charge gate (G2) · no worker
surveillance (G3) · Displacement-Dividend-coupled (G4) · shared-zone speed cap (G5) ·
Murakumo-only (G6) · hazmat segregation raises (G7) · tazuna-teleoperable (G8). See
`CLAUDE.md` for the full text.

Apache 2.0 + etzhayyim Charter Compliance Rider v3.1.
