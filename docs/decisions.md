# Tradebench Decision Log

> One entry per real decision: the choice, the **why**, and a status (Accepted / Provisional /
> Open). Inherited principles are NOT re-recorded here — they live in the PRD (P1–P10) and
> `docs/inherited/decisions-triage.md` (45 prototype decisions triaged carry/lesson/re-decide/
> retire). This log records decisions made in **Tradebench's own context**, starting at D1.

---

## D1 — Inherit by seed pack, not by port — **Accepted** (2026-09-25)

`docs/inherited/` is a read-only snapshot of the prototype's seed pack (PRD, grill records,
playbooks, triage, matured ideas). Tradebench adopts its *lessons and principles*, never its
code. Supersede inherited docs by writing Tradebench docs, not by editing the snapshot.
**Why:** the prototype proved concepts and banked scar tissue; porting Python would import its
structure along with its lessons, which is exactly what the rebuild exists to avoid.

## D2 — Deploy-first: the collection service ships before anything else — **Accepted** (2026-09-25)

The first built and cloud-deployed artifact is the standalone market-data collection service
(IG client + provider port + streaming app + the daily heal→Parquet-archive→digest cycle),
running 24/7. **Why:** data is the only asset the calendar destroys — every unstreamed week is
gone forever (ticks are unrecoverable from IG REST); everything else waits better than data
does. The Python appliance streams in parallel as the shadow-diff oracle. (PRD §7/§11.)

## D3 — One public monorepo, frontend included — **Accepted** (2026-09-25)

`github.com/amfshr/tradebench`: Gradle multi-module Java services + `frontend/` (React/TS, own
toolchain). **Why:** single-checkout context for AI-assisted development, atomic cross-boundary
PRs, one portfolio repo; the frontend has no independent release cadence. Extraction later is
cheap if ever earned. Public = free branch protection + portfolio; the only unpublishables are
credentials and strategy definitions (PRD §9).

## D4 — Messaging: Redis Streams by default; Kafka is a gated re-decision — **Provisional** (2026-09-25)

Inter-service messaging starts on Redis Streams (prototype-proven; fits the ~£20/mo and
minimal-maintenance NFRs). Event contracts are explicitly schema'd so a later Kafka migration is
mechanical. **Re-decide when:** service count, retention needs, or throughput actually demand a
log-structured broker — not before. (Triage: prototype D8.)

## D5 — The name: Tradebench — **Accepted** (2026-09-25)

Not broker-linked (provider identity is abstracted from product identity); lives under
`amfshr.dev` (`tradebench.amfshr.dev`). **Why:** it names the product's centrepiece — the bench
you work at (charts, backtests, the workbench). Collision check done: nearest existing
"TradeBench" products are a manual trading journal and small tools — acceptably sleepy for a
personal product in its own namespace.

## D6 — Daily completeness job is a plain scheduled task, not Spring Batch — **Accepted** (2026-09-25)

The end-of-day cycle (1m-bar REST heal → Parquet export to object storage → digest email) runs
as a Spring `@Scheduled` task writing an audited `job_runs` row on every run, clean or not.
**Why:** Spring Batch's machinery (job repository, chunking, restartability) is for large
multi-step ETL; this is "find gaps, call REST paced, write a file, send an email." The audit row
carries the D29-style doctrine: the *absence* of the row/digest is itself the alarm. Quartz is
the middle step if persistent cron scheduling is ever needed; Batch is not on the path.

## D7 — One DSL grammar; declarative; predicates are boolean indicators — **Accepted** (2026-09-27)

One expression grammar shared by two declaration kinds (`indicator` / `strategy`); the language
is declarative (pure expressions + recursive series definitions — no statements, loops, or
mutation); a predicate is simply a boolean-typed expression, with fitted-shape matching
decomposed into fit-functions returning records plus comparisons. **Why:** one language means
transferable knowledge and one compiler; declarative purity is what makes compile-time
look-ahead rejection (P3) and the batch ≡ step invariant provable rather than aspirational.
Resolves PRD open Q#6. (Design session 2026-09-27.)

## D8 — Compiler/engine shape: EBNF → AST → visitors → dataflow DAG — **Accepted** (ANTLR provisional, 2026-09-27)

Grammar in EBNF, parsed (ANTLR4 — provisional) to our own dumb-node AST (parser-swappable);
compiler passes as visitors (type check, index/look-ahead analysis, warm-up derivation,
volatility marking); lowered to an incremental dataflow DAG whose nodes declare
seed/step/batch, with batch ≡ step byte-identity tested per node family. Teaching-quality
compile errors are a requirement (prototype D33). **Why:** the DAG buys look-ahead rejection,
derived warm-up, workbench fast-fill, and composition by construction — the synthesis of the
industry survey (Pine-family surface on a dataflow engine). (Design session 2026-09-27.)

## D9 — `[0]` is the forming bar; determinism comes from the event clock — **Accepted** (2026-09-27)

Relative index `[0]` denotes the newest bar of the referenced timeframe that has printed data
as-of the evaluation instant — the industry-standard convention both calibrated users expected
— and may therefore be partially formed. Safety is engine-level, not a language ban:
evaluation happens only at deterministic event-clock instants (home-TF seal by default; tick
events in tick-trigger states); forming higher-TF views are always derived from our own finer
atoms by the one shared aggregation (never the broker's forming candle — broker bars stay the
oracle); `closed()` gives explicit sealed-only access; volatile-vs-stable is compiler-marked
and surfaced, never gated (P10); backtests lacking the finer atoms a volatile reference needs
fail loud and named (P9). **Why:** forming data is partial *present* information, not
look-ahead — P3 stands uncompromised (negative indexes remain compile errors); Pine's repaint
disease came from backtests that couldn't reproduce live's samples, which this engine design
removes by construction. (Design session 2026-09-27, ruling by Alex.)

## D10 — AI operating framework: contract, guardrails G1–G8, protocol skills — **Accepted** (2026-09-27)

AI work on Tradebench runs under an explicit contract (`.claude/ai-framework.md`): Alex holds
rulings/merges/money/broker/deploys; AI proposes, implements ticket-scoped work, reviews, and
keeps records — never rules. Eight citable guardrails (public-repo discipline incl. the
strategy-content ban, logged decisions, board as truth, PR-only, testing doctrine, no external
sends, named-target destructive ops, honest reporting); session protocols codified as skills
(`/sanity-lap`, `/design-session`, `/start-ticket`); two project agents (`doctrine-reviewer`,
`seed-pack-librarian`); a standard `.claude/` + `docs/` directory layout. **Why:** AI is the
standing development partner (PRD §9 AI-workable NFR) and Alex's time is bursty — the rules
must hold in every session, however cold, without re-negotiation. Framework changes are
themselves decisions (G2). Enforcement (pre-commit/CI scans, permissions) is E0-T4.
