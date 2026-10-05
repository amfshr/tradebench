# PR #14 review — side task: a testing framework for the resilience belt

Source: the `/code-review ultra 14` run of 2026-10-05, its answer to the brief's side task ("propose a
testing framework for this feature"), recorded verbatim. Companion to
`2026-10-05-pr14-resilience-belt.md` (the 32 findings). Status: a proposal for Alex to rule on; the
board ticket that adopts it will cite this file.

---

The direction in the PR body is right. Fixture-driven scenarios with only the edges replaced would
have caught the two high findings and most of the mediums, because all of them are sequencing
problems between the Supervisor, IgStreamControl and the witness, not errors inside any one core.
Confirm it, with these refinements.

**Drive the threads synchronously.** Do not run the real sweep and pump threads under a fake clock.
Expose `sweep()` and `cycle()` (they are already the units) and let the scenario runner call them in
a deterministic schedule: advance the injected Clock by the sweep interval, run one sweep, deliver
any fixture events due at that time, run one pump cycle, repeat. Callbacks from the scripted
transport are delivered on the runner thread between steps, which models the real ordering (enqueue
on the LS thread, apply on the next sweep) without any waiting or flakiness. The Sleeper becomes a
clock-advancer with a runaway guard, as PumpTest already does. Real threads stay out until a
dedicated concurrency scenario needs them, and even then only with a latch-driven
FakeStreamTransport.

**Where it lives.** A new `market-data-service/src/test/.../scenario` package, since the harness
needs Supervisor, Pump, Buffers, HealthProbe and the stores. Extend the existing FakeStreamTransport
in ig-client's testFixtures with three capabilities it lacks today: scripted per-subscription
outcomes (confirm after N ms, reject with code, stay silent, confirm then go dead), delivery of ticks
and sealed bars to a connection's listeners by item name, and per-handle state so unsubscribing an
inactive handle throws as the SDK does. Consolidate the four FakeClocks and three RecordingEventLogs
into that same fixture set first, since the harness needs one of each.

**Fixture shape.** A timeline per scenario, in YAML or a tiny Java DSL, with one row per event: `at`
as an offset from scenario start, `kind` in {status, tick, bar, confirm, reject, silence, db-down,
db-up, host-sleep, stop}, and the payload. A captured real outage becomes a fixture by converting
its service_events rows plus the Lightstreamer status lines from the log into that timeline, which
is why the one-line status log in the Retry branch matters beyond operability. Expected outcomes
live beside the timeline: the ordered remedy list (resubscribe, rebuild, quarantine, with offsets),
the ordered event list (type, reason, epic), what the sink received and in what order, the heartbeat
row at chosen offsets, and how the run ended.

**Assertion vocabulary.** Assert on those five things only. Remedies issued, events recorded, sink
writes landed, heartbeat state, exit path. Never assert on internal fields of the cores. Chapter 10's
numbers table becomes the expected offsets, which makes policy drift a failing test.

**Postgres.** Testcontainers for the store and hold scenarios, which already exist and should stay.
For everything else a scripted CaptureStore and EventLog that can be told "fail retryably from T1 to
T2" is faster and lets db-down coincide with stream events by the clock, which is the combination
the sweep-thread blocking findings need. Add one Testcontainers scenario that pauses the container
to cover the socket-timeout gap.

**Scenarios to write first, in this order.** Both-legs rejection with a healthy witness (catches
findings 1 and 8). Bare DISCONNECTED after onServerError with CLOSED flags (finding 2).
TRYING-RECOVERY lasting 200s with ticks silent (finding 5, asserting no rebuild before 300s). Wait
with the witness confirming 31s later (finding 4). Weekend zombie: CLOSED flag, then a dead leg, then
the Monday open (finding 3). Dead socket: WILL-RETRY to the 120s rebuild with the watchdog standing
down. Host suspend, wake, WILL-RETRY, rebuild. Postgres restart mid-capture, asserting the held batch
lands and the ladder climbs when it should (finding 6). Shutdown mid-hold, asserting what is lost and
what is logged (finding 20).

**What stays and what goes.** Keep every unit test of the pure cores; they are the fast, exact,
mutation-verified layer and they are not the problem. The SupervisorTest scenario tests that exercise
one detector against FakeStream are the ones the harness subsumes, and they can be deleted once their
scenario exists. The IgStreamControlTest cases stay but should run against the extended fake.

**Cost versus extending per-component tests.** The harness is roughly the fixture extensions, a
200-line runner, and the first five scenarios, perhaps two days. Extending the per-component tests
cannot reach the findings above because each needs two components to disagree, and the fakes that
make those tests fast are precisely what hides the disagreement. The composed harness is the only
layer that can turn chapter 10 into executable expectations, and it is also the layer that makes the
hardening ticket's fixes safe to land together.
