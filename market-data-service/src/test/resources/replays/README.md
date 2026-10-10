# Replays — captured outages as scenario fixtures

A replay is a JSONL file: one event per line, `{"at": <ms from the fixture's origin>, "kind": …,
"payload": {…}}`, loaded by `scenario/Replays.java` into the same `Scenario` the hand-written
policy scenarios use, and run by the same `ScenarioRunner`. The first line is the header
(`kind: replay`): the name, the source, the origin and anchor instants, the epics, `untilMs`
(when the scenario ends — exclusive), and `serverAnswers` — true when the source stored no
Lightstreamer statuses, so the harness greets every connection with `CONNECTED:WS-STREAMING`
and confirms every subscription as a healthy server would. Everything else is what the wire
carried: `tick {epic, bid, ask, dealFlag?}` and `bar {epic, startUtc, bid{open,high,low,close},
ask{…}, ltv?}` — plus, when a source records them, the harness's whole vocabulary (`status`,
`serverError`, `confirm`, `reject`, `dbDown`, `dbUp`, `dbBroken`, `dbWritesRefused`, `hostSleep`,
`igDown`, `igUp`, `stop`).

Two exporters, one per schema (SQL → JSONL), wrapped by `scripts/export-replay.sh` — one command, database →
fixture; the header's two curator sections, `recorded` (what the source recorded, which the expectations may
anchor on) and `modelled` (what the harness supplies because the source did not record it — here the
server's answers), are its `--recorded` and `--modelled` arguments (the T12 nod, 2026-10-10):

- `export-prototype.sql` — the prototype's `igtrader_demo` (`igtrader.dax_ticks`,
  `igtrader.nasdaq_ticks`): bars synthesised per minute from the ticks and sealed only where the
  feed was still alive at the minute's end. **G1:** market data only — never `strategy_events`
  or the OMS tables.
- `export-tradebench.sql` — Tradebench's own `market_data` (`ticks`, `bars_1m` by user and
  source): real bid/ask candles; ticks carry no `DLG_FLAG`, so `dealFlag` is omitted and the
  harness assumes `DEAL`.

Both take `anchor` (the instant placed at offset `before_secs` — pick the last tick before the
thing you are replaying, so the expectations read as `anchor + 90s`), `before_secs`,
`until_secs` and `name`; the commands are in each file's header. Pipe through `jq -c .` to keep
the lines compact.

## The library

| Fixture | What it is | Scenario |
|---|---|---|
| `2026-08-04-silent-while-connected.jsonl` | The 2h56m scar: both markets' feeds died within 584ms of each other at 12:38:08.916Z while the session stayed `CONNECTED:WS-STREAMING`; six minutes of healthy ticks and candles, then the first 100s of the silence. Anchor = the last DAX tick, at offset 360s. | `BeltScenariosTest` — the scar replay |
| `2026-08-04-silent-while-connected-ceiling.jsonl` | The same scar, 60s of healthy ticks then 23 minutes of the silence (origin 12:37:08.916Z; anchor = the last DAX tick, offset 60s): what the belt does with a session that keeps answering `CONNECTED:WS-STREAMING` and sends nothing. | T12 increment 3 (acceptance) |
| `2026-08-05-dax-thin-at-dawn.jsonl` | Dawn, 05:05:00.5Z–05:14:00.5Z: DAX silent 157s (anchor = its last tick, offset 143s), then 72s and 64s, while NASDAQ ticks — one silence over the 90s line, two under it; the prototype resubscribed DAX once (05:08:53.662Z, 90.1s) and recorded `bar_gap` 2. | T12 increment 3 (acceptance) |
| `2026-08-10-dax-quiet-at-dawn.jsonl` | Dawn, six minutes from 05:03:59.9Z: NASDAQ wakes at the anchor (offset 10s) and ticks ~200/min; DAX, silent since 05:02:38.61Z (before the window), stays silent 207s into the window, flag DEAL — one market quiet while the other is alive; the prototype resubscribed DAX twice (05:05:41.53Z, 05:07:11.955Z) and recorded `bar_gap` 4. | T12 increment 3 (acceptance) |
| `2026-09-23-busy-ten-minutes.jsonl` | 11:00Z–11:10Z of the busiest hour: 1727 DAX + 2657 NASDAQ ticks, 16 candles, not one service event recorded 10:50Z–11:20Z — exactly once, nothing remedied, nothing recorded. | T12 increment 3 (acceptance) |

## Acceptance mode (E1-T12)

The same replay through the real `Pump`, `PostgresStore` and `PostgresObservabilityStore` into a
Testcontainers Postgres, the rows read back as the observables (`ScenarioRunner.accept`,
`ReplayAcceptanceTest`, tag `acceptance`). Excluded from the default `test` run; run it with
`./gradlew :market-data-service:replayAcceptance`. Database weather (`dbDown`, `dbUp`, …) is the
scripted stores' to enact — a replay that asks for it in acceptance mode is refused as a harness
error. Expectations are anchored on what the source recorded; a connection answer the capture did
not record is the harness's healthy-server mode speaking, named as such (the T12 nod, 2026-10-10).

**Curation (E1-T12 increment 2, 2026-10-10).** The windows are chosen from what the prototype's record can
prove (the T12 nod, ruling 2): silences and their edges, tick counts, `bar_gap` events, its own watchdog's
resubscribes — never a connection sequence. Dropped, not modelled: the host-suspend outages (the record shows
the host awake and without network inside the gaps, and its `offline_seconds` do not match the tick gaps, so
the record cannot say how long the host slept — the clock-divergence mechanism stays proven by the
`BeltScenariosTest` host-suspend scenarios) and the weekend `CLOSED → open` transition (neither source stored
the deal flag: the prototype's ticks all read `DEAL`, Tradebench's `ticks` table has no flag column — our
`MARKET_STATE_CHANGE` events do record the transitions, so a future exporter can join them). The
`tradebench` branch of the script is written but unexercised until Tradebench's own capture holds an outage.
