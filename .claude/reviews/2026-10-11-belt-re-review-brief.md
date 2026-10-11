# Review brief — the resilience belt, re-reviewed after E1-T10, E1-T11 and E1-T12

**Review only. Do not merge.** This PR exists to give one independent multi-agent review
(`/code-review ultra 24` — this is PR #24) a single diff that spans the whole belt and everything done
to it since the first review. Its base is `review-base/ee35f9b` — `main` just before the belt's first
PR merged — and its head is `review/belt-after-t12`, a copy of `main` on 2026-10-11. Everything in it is
already merged. The PR is a draft so it cannot be merged; close it and delete both branches once the
review has been read. The local session (Alex + Claude Code) triages and fixes; the review session
reviews.

**What a pass means.** This is the single re-review ruled on 2026-10-08 (E1 plan T10 § close-out
ruling) and clarified on 2026-10-09/10: it runs once, after all of T10 and T12. **A pass gates E1-T6**
(daily heal, archive, digest). Give an explicit verdict: **PASS**, **PASS-WITH-FINDINGS** (naming which
findings must be fixed before T6 starts and which can ride a later ticket), or **FAIL** (what must be
redone). Say it in one line at the top of the report.

## The range

`ee35f9b` → `main` (`d8ca9de`, which includes this brief): 170 files, +25,912 / −749, eleven merged PRs —

| PR | What | Merged |
|---|---|---|
| #11, #12 | E1-T5 slices A–C: the belt (cores, Supervisor, IgStreamControl, HealthProbe, the observability store) | 2026-10-03 |
| #13 | E1-T9: the capture sink holds through a database blip | 2026-10-04 |
| #15 | E1-T10 A1: the first review's two highs | 2026-10-08 |
| #16 | E1-T11: the scenario harness (`ScenarioRunner`, `Scenario`, `Observed`, `Replays`, the first captured fixture) | 2026-10-08 |
| #17 | E1-T10 A2: detectors and verdicts, as scenarios (12 findings) | 2026-10-09 |
| #19 | E1-T10 B: the sink and the database edge, as scenarios (9 findings) | 2026-10-10 |
| #20 | E1-T10 C: loudness, reuse, docs (8 findings) — T10 complete, every finding resolved | 2026-10-10 |
| #21, #22 | the real account ids, the IG identifier and the instance name redacted to synthetic values (G1) | 2026-10-10 |
| #23 | E1-T12: the captured-outage library and the rig in acceptance mode; four belt defects the replays found, fixed | 2026-10-11 |

## The record to review against

- **The first review:** `.claude/reviews/2026-10-05-pr14-resilience-belt.md` — 33 ranked findings
  (#1–#32 from the review, #33 added by the harness's first replay), every one now fixed or answered;
  each fix's slice, test and mutation evidence is on the board (`.claude/tasks/board.md`, Done history
  2026-10-08 → 2026-10-11) and in the E1 plan (`.claude/tasks/epics/e1-collection-service.md`, T10 §,
  T11 §, T12 §). The review's side-task proposal that became the harness:
  `.claude/reviews/2026-10-05-pr14-testing-framework.md`.
- **The policy:** `docs/decisions.md` **D27** (the belt's operating contract — DB-write tiers,
  exhaustion as a time budget, one streaming rule), **D28** (Tier 1 refined: hold-and-retry, the blip
  taxonomy, no time budget), **D29** (the first review's rulings — the bounded CLOSED stand-down, the
  JDBC socket timeout, Tier-2 writes off the sweep thread, the hush retired — plus (5), 2026-10-10/11:
  the session ladder closes only on data from a market that was stale at the verdict, capped at
  10 minutes), **D25** (the event catalogue; amended 2026-10-10 with `connection_status`).
- **The T12 nod** (E1 plan T12 §, 2026-10-10): replay what was recorded, model what was not and label it
  (each fixture header's `recorded` / `modelled`); the fixture set leans to what the captures can
  prove; raw status transitions are recorded from now on; the ladder closes on data.
- **The teaching book:** `docs/field-manual/market-data-service/10-the-failure-playbook.md` (scenario-first
  policy, every clock, the threads and the one race, the DB-write tiers, the dated rulings, "The replay
  library"), chapter 08 (the belt — detectors, the ladder, healing), chapter 04 (the pump), chapter 05
  (the store), foundations chapter 02 (threads) and 07 (clocks). The doctrine: `CLAUDE.md`,
  `docs/inherited/prototype-engineering-playbook.md` §2, `.claude/ai-framework.md` (G1–G8).

## What to look at, hardest first

1. **Did the 33 fixes fix the 33 findings?** For each: the fix as merged, the exposing test the slice
   names, and the mutation claim ("applied → red → restored"). Re-verify a sample adversarially — at
   least the two highs (#1, #2), the three concurrency findings (#11, #13, #19), the ladder/ceiling
   semantics (#18), the sink's recovery round trip (#6), and #33 (one verdict for two feeds dying
   together). Say where a fix answers the letter of a finding but not its risk.
2. **The harness as an instrument.** `market-data-service/src/test/java/.../scenario/`: the runner's time
   model (time passes only in sleeps; the hook order deliver → sweeps → writer → heartbeats; the 1ns
   advance), the healthy-server answer (`serverAnswers`) and what it hides, `HarnessError` vs a verdict
   on the system, the `Stores` seam and acceptance mode (`PostgresStores`: the rows read back as the
   observables — the SQL, the dedupe-aware tick count), the pump's last turn at a clock-ended run.
   Where could the instrument lie green?
3. **The replays and what they prove.** The five fixtures under `market-data-service/src/test/resources/replays/`
   and `ReplayAcceptanceTest`: is every expectation anchored on the fixture's `recorded` header and
   chapter 10's ruled timings, never on what the code happened to do? Is anything modelled that the
   record could have supplied? Are the two drops (host-suspend windows; the weekend transition) justified
   by the record as the README's curation note claims? Does the exporter (`scripts/export-replay.sh`,
   the two SQL files, `ExportTradebenchSqlTest`) read market data only (G1) and label what it models?
4. **The new policy's consequences.** Ruling 4 (`StalenessWatchdog`: `fedSinceVerdict`, `staleAtVerdict`,
   `sessionGraceCap`), the two bar-clock fixes (a market returning from a judged silence; a market
   leaving its stand-down), `connection_status` on every transition (volume, the console line per
   status, the Tier-2 queue), the pacer's log-only decorator, `BestEffortEventLog` as a type. Look for
   what each could break that no scenario covers — the quiet-but-open market, three or more markets,
   a weekend, a host that sleeps mid-rebuild, a rebuild that connects and never confirms.
5. **Concurrency, failure semantics, queue contracts** — the first brief's §1–§3 still apply and are
   still the hardest: four threads and their fences; every exception path classified retryable vs
   terminal and what is held, lost or doubled; shed-oldest ticks, ack-after-apply bars, the store-held
   batch acknowledged only once landed. The code moved under them (Tier-2 writes off the sweep thread
   through `QueuedEventLog`; the generation gate; `StreamControl.Outcome`; the batched heartbeat;
   `PostgresStore.recover()` landing the held batch) — judge the moved code, not the old picture.
6. **Public-repo guardrails (G1).** No credentials, no `.env*` values — everything in `.env` is
   sensitive by definition (URLs, usernames, account ids, instance names; the rule of 2026-10-10,
   `.claude/ai-framework.md` G1): run the `.env` leak check from the doctrine-reviewer's checklist and
   report a hit by variable name only; no real strategy content; the fixtures carry synthetic ids only.
7. **Docs truth.** Chapters 04, 05, 08, 10 and D25–D29 against the code as merged: every cited name,
   number and path. A chapter that still describes the old picture is a finding.

## Known and open, so you need not re-raise them (disagree if you do)

- **Open for Alex, by his choice:** the `boolean recover()` mechanism from slice B's review (finding 3
  there) — `CaptureStore.recover()` returns whether it landed held writes, so a proven episode closes at
  once; the mechanism was taken on-branch, the decision stands open.
- **Backlog B10:** `Jdbc.lookupId`'s refusal of an unknown user/source ("refusing to invent one") is
  unpinned — a pre-existing gap, scheduled, not scope here.
- **Accepted as fine (Alex, 2026-10-10):** a boot into a quiet-but-open pair earns one session rebuild at
  90s; no fixture exercises it since the dawn window was re-cut.
- **Dropped by ruling, not forgotten:** host-suspend fixtures (the prototype's record shows the host awake
  and without network inside the gaps; its `offline_seconds` do not match the tick gaps) and the weekend
  `CLOSED → open` transition (neither source stored the deal flag; our `MARKET_STATE_CHANGE` events do).
- **The `tradebench` exporter branch** is exercised by a Testcontainers test only; no outage of our own
  has been captured yet.
- **The review-only lane** (`replay-acceptance.yml`) is nightly and on demand, not a required check.

## Running things

```
./gradlew build                                      # 350 tests; the acceptance suite is excluded
./gradlew :market-data-service:replayAcceptance      # 6 tests through a Testcontainers Postgres (Docker)
(cd web && npm test -w docs)                         # 65 tests incl. the dead-link walk and the board parse
```

## What to hand back

Ranked findings, most severe first, each with file:line, the concrete failure scenario (inputs/state →
wrong output), a one-line fix, and a verdict (CONFIRMED / PLAUSIBLE, as the first review did); then
what you checked and found sound, and the one-line gate verdict (PASS / PASS-WITH-FINDINGS / FAIL).
Write it in the shape of `.claude/reviews/2026-10-05-pr14-resilience-belt.md`; the local session saves
it as `.claude/reviews/2026-10-11-belt-re-review.md` and triages it onto the board (Alex rules).
