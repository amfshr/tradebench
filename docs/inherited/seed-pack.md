# Seed Pack — what to feed the new repository, and in what order

> **What this is.** The handoff index for spinning up the Java platform's repository. Copy the
> files below into the new repo (suggested home: `docs/inherited/`), read them in the order
> given, and the new project starts with the prototype's full evidence base instead of a blank
> page. Assembled 2026-09-24, the day the grill sessions closed.

## The one-action handoff

From this repo's root, the whole pack is `tmp/java/` — copy the directory. Everything in it is
public-safe (no credentials, no strategy content beyond what Dad has already shared for
calibration). The reading order below is the curation.

## Reading order

**Tier 1 — the product truth (read first, in this order):**

1. [`product-requirements.md`](./product-requirements.md) — THE PRD. Vision, users, the ten
   named principles (P1–P10), the platform surface, phasing, non-goals, open questions.
2. [`grill/`](./grill/) — the five answered grill-session records the PRD derives from
   (session-1-vision → session-5-execution-and-platform). Where the PRD and a record disagree,
   the record wins.

**Tier 2 — the lessons (the prototype's evidence base):**

3. [`ig-broker-playbook.md`](./ig-broker-playbook.md) — everything learned about IG: session/
   account gotchas, streaming resilience, dealing-rule constraints. The single most load-bearing
   lessons doc; the IG adapter and the broker simulator are both specified by it.
4. [`prototype-engineering-playbook.md`](./prototype-engineering-playbook.md) — the non-broker
   lessons: architecture doctrine, testing doctrine (incl. the AI-self-confirmation audit),
   config/secrets discipline, ops scar tissue, process patterns.
5. [`decisions-triage.md`](./decisions-triage.md) — all 45 prototype decisions triaged
   carry / carry-as-lesson / re-decide / retire. The Carry group seeds the platform's own
   decision log; the Re-decide group is the design-phase checklist.

**Tier 3 — matured design material (pre-dates the grill; read with the PRD in hand):**

6. [`ideas/event-source-and-clock-model.md`](./ideas/event-source-and-clock-model.md) — the
   one-engine-many-clocks foundation (live / replay / fast-forward). Load-bearing for §6 of the
   PRD.
7. [`ideas/indicator-library.md`](./ideas/indicator-library.md) — indicator family hierarchy,
   warm-up semantics, bulk≡step guarantee. Input to the scheduled indicators-and-predicates
   deep-dive (PRD open question #5).
8. [`ideas/visual-research-workbench.md`](./ideas/visual-research-workbench.md) +
   [`visual-workbench-for-richard.md`](./visual-workbench-for-richard.md) — the exploration-mode
   thinking (largely absorbed into PRD §5.2; the Richard alignment doc is parked pending Dad's
   return from the hiatus).
9. [`data-platform-design.md`](./data-platform-design.md) — parked design notes for the
   data-collection service (capture the finest atom, derive the rest; PRICE + CHART:1MINUTE
   subscriptions; Parquet thinking). Consistent with grill S4/S5 outcomes.
10. [`research/market-data-providers-2026-08.md`](./research/market-data-providers-2026-08.md) —
    the concluded provider survey: IG = broker truth, Databento `XEUR.EOBI` = market truth,
    Dukascopy free archive, the monthly-batch-pull design with spend caps.
11. [`design/phase1-market-data-build-plan.md`](./design/phase1-market-data-build-plan.md) —
    the pre-pivot phase-1 build plan (Gradle module map, playbook porting order, AWS analysis).
    ⚠️ Written before the grill: still useful for module topology and the shadow-diff idea, but
    the PRD's §11 phasing and the S5 environment decisions (staging+prod, monorepo-with-frontend
    ⚖️) supersede its framing where they conflict.

**Also relevant, staying in this repo (reference by URL, don't copy):**

- `docs/decisions.md` — the full prototype decision log (the triage's source; the authority on
  each original rationale).
- `docs/architecture.md` + `docs/` service docs — the reference implementation's design record.
- `tmp/model-context/p1/` and `tmp/model-context/rangebar/` — the two real strategy
  specifications that are the DSL's acceptance anchors (PRD §6). Strategy *content* stays out of
  the public new repo; these are referenced for design, not shipped.
- `tmp/parked/validation-and-data-programme.md` — the old backtesting/fill-model programme notes
  (largely absorbed by the PRD, kept for detail).

## Standing facts the new repo should know on day one

- The Python system lives on ONLY as a data-capture appliance (MacBook Air, mains power,
  market-data service + infra; engine/OMS disabled) — every day it runs adds to the platform's
  historical IG dataset, and it is the ready-made shadow-diff oracle for the Java collection
  service when that lands.
- Dad is on a ~3–6 month trading hiatus (as of late Sep 2026). Nothing blocks on him; his
  return is a *designed-for event* (PRD §2), not a dependency.
- **Deploy-first ruling (Alex, 2026-09-25):** the collection service (IG client + provider port
  + Spring Boot streaming app + the daily heal→Parquet-archive→digest cycle) is the first thing
  built and cloud-deployed — 24/7 capture starts before anything else exists (PRD §7/§11).
- The spawned design session (indicator/predicate vocabulary) is the first design-phase task —
  Alex explicitly requested it (grill S3-Q1).
- Product name: **Tradebench** (chosen 2026-09-25) — `github.com/amfshr/tradebench` (public
  monorepo incl. frontend, Alex's account), `tradebench.amfshr.dev`.
