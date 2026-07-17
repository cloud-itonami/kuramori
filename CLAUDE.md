# kuramori 倉守 — warehouse intralogistics robotics (AGV/AMR)

**ADR**: 2606142000 · **depends**: 2606082000 (niyaku — AGV/dispatch core reused) ·
2606042300 (todoke — last-mile sibling) · 2606073001 (robotics remote-work coverage/GAP
survey — names `kuramori 倉守` as the warehouse GAP) · 2606032130 (Displacement Dividend) ·
2606042100 (tazuna — teleop substrate) · 2605215000 (Murakumo-only) · 2605312345 (Datom =
canonical state). **Status**: 🟡 R0 design + sim only.

kuramori ("倉守" = warehouse-keeper) is the **standalone warehouse-handling body** the
roster GAP-mapped to (ADR-2606073001 §3 — 積み下ろし was only *partial*: niyaku's 積込ロボット
covers the quay, nothing covered the warehouse floor). It closes the leg between **niyaku 荷役**
(port quay↔yard) and **todoke 届け** (last-mile): AGV/AMR horizontal transport, ABC
velocity-based slotting, putaway feasibility, and pick-route + fleet dispatch.

**Clojure-first.** kuramori is the reference actor for the Clojure-first GAP-actor wave —
the first actor whose methods are authored directly in babashka-runnable Clojure (not ported
from Python). Methods are pure (no deps) → run under both `bb` and the kotoba pywasm runtime.

## Hard gates (constitutional — read before any change)

- **G1 — design + sim only.** R0 is pure planning compute; it moves no real robot. Real
  actuation is Council Lv6+/operator-gated R1 (no-server-key). The methods never touch a
  network or a device.
- **G2 — zero-emission.** Electric AGV/AMR only; regenerative braking credited; the
  opportunity-charge gate (`needs-charge?`) keeps SoC above the reserve floor. No
  LPG/diesel forklifts.
- **G3 — no worker surveillance.** KPI is throughput / moves-per-hour (an *equipment* metric),
  never a per-picker pace ranking or biometric. (Mirrors niyaku G11/G14.)
- **G4 — dividend-coupled.** Warehouse-labour displacement is coupled to a funded
  Displacement-Dividend cohort (ADR-2606032130 G2). No live displacement without it.
- **G5 — shared-zone safety.** A robot inside a human-shared zone is hard-capped at
  `shared-zone-cap-mps` (1.5 m/s); the cap is **not tunable up** by a planner. Enforced in
  `agv-amr/effective-vmax` + test.
- **G6 — Murakumo-only** narration/inference (ADR-2605215000).
- **G7 — hazmat segregation.** `slotting/putaway-feasible?` enforces weight-on-shelf,
  temperature class, and hazmat segregation; `assign-slot!` **RAISES** when no feasible slot
  exists — an infeasible putaway must surface, never be silently forced (mirrors niyaku
  StowError discipline).
- **G8 — tazuna-operated.** Remote operation/teleop is via tazuna 手綱 (ADR-2606042100);
  weaponizable use is unrepresentable.

## Layout

```
20-actors/kuramori/
├── CLAUDE.md                       # this file
├── manifest.edn                    # actor manifest (5 cells, 8 gates, Clojure methods)
├── data/
│   └── warehouse.edn               # reference mixed-temperature DC seed (:representative)
├── methods/                        # pure Clojure → bb-runnable AND kotoba-pywasm-portable
│   ├── agv_amr.clj                 # AGV/AMR motion + dispatch + battery (ports niyaku)
│   ├── slotting.clj                # ABC slotting + putaway feasibility + pick-route
│   ├── picking.clj                 # multi-order batch consolidation (G9) + congestion (R1)
│   ├── handoff.clj                 # cross-actor chain 縁 niyaku→kuramori→todoke (G10, R1)
│   ├── analyze.clj                 # end-to-end R0 orchestrator
│   ├── datom_emit.clj              # kotoba EAVT Datom-log emitter (canonical state)
│   └── test_kuramori.clj           # 15 tests / 43 assertions (clojure.test)
└── lex/
    └── moveAttestation.edn         # per-move warehouse handling attestation lexicon
```

## Run

```bash
# from repo root (classpath = 20-actors, ns = kuramori.methods.*)
bb --classpath 20-actors 20-actors/kuramori/methods/test_kuramori.clj   # 15 green
bb --classpath 20-actors -m kuramori.methods.analyze                    # → report
bb --classpath 20-actors -m kuramori.methods.datom-emit                 # → EAVT Datom log
```

## Why niyaku's core is reused, not reinvented

The AGV horizontal-transport mathematics — trapezoidal/triangular travel-time, one-way
segment-conflict, greedy LPT makespan dispatch — was already proven in niyaku's
`agv_transfer.py` (ADR-2606082000). kuramori ports those exact semantics into Clojure and
**extends** them for the warehouse: AGV (fixed path, segment reservations) vs AMR
(free-roaming, shared-zone yield), a battery SoC/opportunity-charge gate (G2), and the
collaborative-safety speed cap (G5). The slotting/putaway/pick layer is new (warehouse, not
quay). When niyaku's core changes, re-check the ported invariants here.
