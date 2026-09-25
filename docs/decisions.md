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
