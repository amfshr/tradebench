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

Two exporters, one per schema (SQL → JSONL; T12 wraps them in a script and curates the library):

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
