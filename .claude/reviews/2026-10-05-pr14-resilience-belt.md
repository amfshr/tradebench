# PR #14 review findings — the resilience belt (E1-T5 A–C + E1-T9)

Range reviewed: `base/pre-belt` `ee35f9b` → `review/resilience-belt` `9746737` (103 files, +7492/−599).
Method: eleven finder angles plus one independent single pass, ~50 deduplicated candidates, eight
adversarial verifiers tracing each path against the code and the design record (D25–D28, chapter
08/10, playbook §3). Ranked most severe first. Verdict CONFIRMED unless marked PLAUSIBLE (the one
finding resting on the Lightstreamer SDK's documented throw-on-inactive contract; the jar was not
available to read).

Refuted against the design record and therefore NOT listed: state changes leaving the JSONL file
and the capture store (decision #1, D27 Tier 2), the unconditional state-change ack, holding forever
on class 53 (D28), the dropped event_type index and the capture_status key shape (D25 as specced),
the V2 backfill, the IG application-allowance array shape, invalid-details as fatal, the
shutdown-hook race with a live-but-unclosed connection (the interrupt breaks the login gate), and
the main-thread re-login after threads start.

---

## High

### 1. Quarantined market is re-admitted and resubscribed forever
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:183`

A SubscriptionError for an already-quarantined epic is fed to the witness with no quarantine gate, WitnessQuarantine.onSubscriptionError recreates the market via computeIfAbsent with strikes=1, the Retry judgment calls stream.resubscribe, and IgStreamControl.resubscribe never consults its quarantined set, so the quarantined pair is re-subscribed and the cycle repeats.

Scenario: each market is two Lightstreamer subscriptions (PRICE+CHART), so one rejected attempt enqueues two SubscriptionErrors. DAX bad, NASDAQ confirmed: strikes 1,2 → Retry/resubscribe; strike 3 (pair 2's PRICE) → Quarantine: MARKET_QUARANTINED written, forget(DAX), stream.quarantine drops the legs (WitnessQuarantine.java:73-75, Supervisor.java:258-268). Pair 2's CHART error is already queued and same-generation (gate only bumps in closeStream) → Supervisor.java:183 → WitnessQuarantine.java:61 computeIfAbsent → strikes=1 → Retry → Supervisor.java:241-242 → IgStreamControl.java:131-143 has no quarantined check, legs has no DAX, subscribePair re-subscribes the bad pair → two more rejections → re-quarantine → twin error → re-admit… an unbounded subscribe/reject storm against IG with duplicate MARKET_QUARANTINED rows and no backoff (Retry bypasses rebuild()'s pacing).

Fix: drop SubscriptionErrors for epics in quarantinedMarkets in sweep(), and make IgStreamControl.resubscribe refuse quarantined epics. Exposing test: SupervisorTest.failToTheStrikeCeiling with 4 errors instead of exactly 3.

### 2. Bare DISCONNECTED / onServerError trigger no remedy
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:175`

A bare terminal DISCONNECTED and onServerError produce no rebuild: ServerError only records IG_API_ERROR, bare DISCONNECTED clears StuckSubstateEscalator's substate so rebuildDue is never true, consecutiveFailures stays 0 so the budget check is inert, and the only remaining detector is the watchdog, which is suppressed while a market's last DLG_FLAG reads CLOSED/SUSPEND, contrary to playbook §3.1 ('the first onServerError marks the connection dead… a bare DISCONNECTED is also dead').

Scenario: overnight, both markets' last tick carried DLG_FLAG=CLOSED. LS emits onServerError then 'DISCONNECTED' (retries exhausted). Supervisor.java:175-179 writes IG_API_ERROR only; applyStatus → ReconnectClassifier.onStatus opens an outage (inOutage is private, nothing acts on it); StuckSubstateEscalator.java:31 sets substate=null for plain DISCONNECTED; Supervisor.java:186 budget check inert with consecutiveFailures==0. checkStaleness: Buffers.lastDealFlag stays CLOSED because no tick can arrive, StalenessWatchdog.java:160 returns null for every market → no RESUBSCRIBE/REBUILD/FEED_DEAD ever; heartbeat shows RECONNECTING through the next open and nothing in this repo restarts daily.

Fix: treat ServerError and a non-substate DISCONNECTED on the live generation as a rebuild trigger (consecutiveFailures++ or a direct rebuild); add a SupervisorTest feeding a bare 'DISCONNECTED' with CLOSED flags and asserting a rebuild.

## Medium

### 3. Stale CLOSED flag disables watchdog and alarm indefinitely
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/StalenessWatchdog.java:160`

The CLOSED/SUSPEND stand-down reads the last DLG_FLAG ever delivered (Buffers.lastDealFlag, re-fed every sweep) and nothing — rebuild, resubscribe, or time — invalidates it, so a leg that dies while a market reads CLOSED is invisible to the watchdog, and because HealthProbe publishes the same flag as market_state the chapter-10 dead-feed alarm is suppressed too.

Scenario: Friday 22:00 DAX's last update carries CLOSED (Buffers.java:72). Over the weekend IG drops the item server-side while the session stays CONNECTED:WS-STREAMING (the §3.4 zombie). Monday 08:00 the open never arrives: no tick → flag stays CLOSED → Supervisor.java:213-216 feeds onDealFlag(DAX,'CLOSED') each sweep → staleSignal returns null forever; escalator sees no substate; HealthProbe.snapshot writes market_state='CLOSED' and chapter 10 says never alarm on CLOSED. A full session of capture is lost until an unrelated rebuild.

Fix: bound the stand-down (stop suppressing once the flag is older than N hours, or issue the documented-harmless resubscribe on a long-stood-down market so its snapshot teaches the real flag); clear lastDealFlag on rebuild/resubscribe.

### 4. Judgment.Wait is never re-armed; pair stays unsubscribed
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:244`

Judgment.Wait consumes the rejection (and WitnessQuarantine decrements the strike) and nothing re-presents it when the witness confirms or the 30s window lapses, so the failing pair stays unsubscribed until the watchdog independently fires at ≥90s rather than the ≤30s §3.5 implies, and indefinitely if that market's last DLG_FLAG was CLOSED/SUSPEND, while stateOf() still reports CONNECTED_STREAMING.

Scenario: after a rebuild (Supervisor.java:343-346 re-arms survivors), DAX is rejected three times while NASDAQ is <30s into its confirm window → WitnessQuarantine.java:77-86 strikes-- and Wait → Supervisor.java:244-247 no-op; the twin-leg error → Wait again. onSubscribed(NASDAQ) only adds to confirmed, window lapse triggers nothing, and Lightstreamer sends no further onSubscriptionError for an already-rejected Subscription. DAX is unsubscribed; recovery depends on tickSilent at 90s, which StalenessWatchdog.java:160 suppresses if DAX's frozen flag is CLOSED (the overnight norm).

Fix: keep a 'pending verdict' per market and re-judge it on the witness's confirm or window expiry (issue the Retry resubscribe then). Exposing test: SupervisorTest.aWitnessStillInsideItsConfirmWindowMakesTheFailureWait advanced 31s and asserting a resubscribe or verdict.

### 5. Watchdog's 90s verdict pre-empts 300s replay patience
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:194`

checkStaleness() runs every sweep with no connection-state guard, so with ≥2 open markets the watchdog's 90s session verdict rebuilds a DISCONNECTED:TRYING-RECOVERY session at T+90s, discarding the 300s lossless-replay patience that StuckSubstateEscalator, chapter 08 (lines 115-118) and chapter 10 (table row 41) promise.

Scenario: two markets with DLG_FLAG=DEAL; status goes DISCONNECTED:TRYING-RECOVERY at T+0 and ticks stop. stuck.rebuildDue stays false until T+300s, but at T+90s both markets are TICK_SILENT (StalenessWatchdog.java:163) → stale.size()>=2 → REBUILD → rebuild(WATCHDOG_STALE) → rebuilding() sets dataGone=true and stream.rebuild() tears down a session Lightstreamer may still have replayed; RECONNECT{replayed=false} and a heal are now owed. With one market the ladder spends RESUBSCRIBEs on the recovering client instead. The pre-emption also applies to WILL-RETRY (90s vs 120s).

Fix: stand the watchdog down (or baseline it) while the escalator holds a substate / while !isStreaming(lastStatus). Exposing test: twoMarketsStaleTogetherRebuildTheSession with a TRYING-RECOVERY status applied first, asserting no rebuild before 300s.

### 6. recover() needs no round trip, so backoff resets every 5s
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/ingest/Pump.java:159`

PostgresStore.recover() does no server round trip once instrument ids are cached (getConnection + client-side prepareStatement + addBatch), so it 'succeeds' against a database that accepts connections but rejects writes (53100 disk_full or any 53/57 error that is not a connection loss); each flush failure then ends the hold episode and the next hold() restarts at attempt=1, a permanent 5s-cadence fail/recover loop that never climbs the ladder.

Scenario: disk full: flush() → executeBatch throws 53100 → retryable (PersistenceException.java:19 contains '53') → hold(): attempt=1, 5s floor → recover() (PostgresStore.java:139-144): Hikari hands out a validated idle connection, prepare is client-side, bind() uses the cache → returns normally; 'sink recovered after 5s' logged, SINK_FAILURE attempted (fails too); next cycle flush fails again → new hold(), attempt=1 again. Net: a recover/fail loop every 5s with ~720 'recoveries'/hour, no escalation to the 60s cap. Holding forever is D28's ruling and fine; the defect is that recover() cannot distinguish a healthy database from one rejecting writes.

Fix: make recover() do a round trip (execute the held batch inside it, or at least SELECT 1), or carry attempt/since across consecutive episodes until a write actually lands.

### 7. No JDBC socketTimeout: half-open connection hangs the pump
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/Database.java:94`

Database.connect sets only connectionTimeout; pgjdbc's defaults socketTimeout=0 and tcpKeepAlive=false apply (no override anywhere in java/kts/env), so on a half-open TCP connection the pump's executeBatch blocks indefinitely with no SQLException, which the hold-and-retry design — whose whole premise is that a sink failure surfaces as a retryable PersistenceException — cannot see.

Scenario: DB host vanishes without RST (VM pause, NAT idle drop, host crash) after acking the last bytes. PostgresStore.flush() → tickInsert.executeBatch() (PostgresStore.java:125) waits forever; no hold, no SINK_FAILURE. The pump thread is alive, parked in a socket read, so Main's !pumpThread.isAlive() check never fires; heartbeats print with written= frozen and queues growing; HealthProbe writes on other pooled connections (validated on borrow) keep capture_status fresh, so the box-down alarm does not fire either. On Ctrl-C the 5s join fails. Database.java predates the PR, but T9 makes it newly load-bearing.

Fix: config.addDataSourceProperty("socketTimeout", …) sized above the longest legitimate statement, plus tcpKeepAlive=true, on the pool and on dedicatedConnection(); add a store test that pauses the Testcontainer and asserts a PersistenceException within the timeout.

### 8. Strikes counted per leg rejection, not per attempt
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/WitnessQuarantine.java:62`

strikes++ runs once per leg rejection, so a market whose PRICE and CHART are both rejected (the normal bad-epic case) hits the 3-strike verdict after the initial attempt plus one real retry, and the sweep issues two back-to-back resubscribes for one attempt, discarding the first retry's pair before the server can answer — contrary to playbook §3.5 ('initial subscribe + 2 surgical retries, fresh pair, fresh confirm window').

Scenario: both legs of DAX rejected at connect; both errors drained in one sweep (Supervisor.java:172-184): PRICE err → strikes=1 → Retry → resubscribe #1; CHART err (same attempt) → strikes=2 → Retry → resubscribe #2 (drops pair 1 microseconds after creating it). Pair 2's first leg rejection → strikes=3 → verdict. Net: one genuine retry instead of two and two control requests per sweep for one attempt. Tests feed kind-less errors exactly 3 times so the per-attempt model is never exercised.

Fix: carry the leg Kind on onSubscriptionError (as onSubscribed already does) and strike once per (epic, attempt) — e.g. ignore an error whose attempt stamp predates the market's latest onSubscribeStarted.

### 9. Retry rejections and LS status leave no log line at all
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:240`

The Supervisor has no log consumer: a sub-ceiling rejection (Judgment.Retry) produces neither an event nor a log line in any mode, and the base Main's stdout lines for LS status changes, server errors, 'subscribed:' and 'SUBSCRIPTION ERROR' were removed with no decision recording it, so in jsonl mode (eventLog = event -> {}) a mistyped epic, a server error or a quarantine is invisible on stdout — against chapter 10's 'a failure is never silent'.

Scenario: TRADEBENCH_EPICS has a typo and the other market is healthy. Strikes 1-2 → Retry (Supervisor.java:240-243) with no record() and no log; strike 3 → MARKET_QUARANTINED event only (Supervisor.java:259). In jsonl mode Main.java:103 discards it and the heartbeat summary (Main.java:226-235) has no per-market state, so stdout never says why one market captured nothing. In db mode the first two rejections of every market leave no trace. The row-not-log direction is ruled (decision #1, ch08:213) and stays.

Fix: one log line in the Retry branch and on SUBSCRIPTION_REJECTED/MARKET_QUARANTINED/IG_API_ERROR via the same message -> log(instance, message) consumer IgStreamControl already takes.

### 10. ~31 lines of JDBC helpers copied verbatim from PostgresStore (reuse)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/PostgresObservabilityStore.java:146`

instrumentId() (146-160), lookupId() (162-174) and utc() (180-182) are verbatim copies of PostgresStore.java:186-199, 203-216 and 239-241 in the same package; this PR re-signatured PostgresStore's helpers to be connection-agnostic and then copied them instead of extracting.

Cost: the instruments upsert-then-select SQL and the 'schema seeds are the source of truth; refusing to invent one' rule now live in two places; a change to the instruments dimension must land twice and the schema-drift test only catches the copy a test exercises; the copies already differ in cache type (HashMap vs ConcurrentHashMap).

Fix: a package-private InstrumentIds (constructor takes the Map so each store keeps its cache type) plus static lookupId/utc/utcOrNull in a small Jdbc holder — same SQL, same exceptions, no caller change.

## Low

### 11. Old-connection observations applied after rebuild (no epoch)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:172`

The generation gate lives only in IgStreamControl's callbacks and Observations carry no generation, so anything the dying connection enqueues between the end of the drain and closeStream() inside stream.rebuild() is applied in the next sweep after rebuilding()/onSessionRebuilt(); the window is microseconds normally but up to ~5s per blocking record() (line 333, before stream.rebuild) while Postgres is down.

Scenario: Postgres down, old client in TRYING-RECOVERY. Sweep N decides REBUILD; rebuild() blocks 5s in record(reasonEvent) before closeStream bumps the generation; in that window the old client's recovery completes and enqueues CONNECTED:WS-STREAMING (gated live). rebuilding() opens the outage with dataGone=true; stream.rebuild() blocks ~61s in the login gate. Sweep N+1 applies the stale CONNECTED: outage closed, consecutiveFailures=0, lastRebuildMono reset, RECONNECT written, streaming=true, substate cleared — BeltView says CONNECTED_STREAMING while no connection exists. The same window lets an old leg's Subscribed/SubscriptionError land on the fresh WitnessQuarantine.

Fix: stamp Observations with the generation (IgStreamControl already has it) or drain-and-discard the queue once more right after stream.rebuild() returns.

### 12. Sync Tier-2 writes block the sweep thread up to 5s each (efficiency)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:333`

record() is a synchronous pooled write that blocks up to Database.CONNECTION_TIMEOUT (5s) per event while Postgres is down and sits before stream.rebuild() in rebuild(); two such writes in one sweep push the next watchdog round over processFreezeJump (10s) and rebaseline every staleness stopwatch, and chapter 08's 'nothing blocks; the pass is microseconds of CPU' is false on two counts (record() and stream.rebuild()).

Scenario: Postgres down while the stream degrades: a sweep that writes TRANSPORT_DOWNGRADED then RECONNECT (lines 272, 282), or MARKET_QUARANTINED then a refused-unsubscribe rebuild reason (259, 333), holds the sweep ~10s; the next evaluate() sees monoDelta > 10s (StalenessWatchdog.java:148) → rebaseline(): every market's lastTick/lastBar = now, restarting the 90s/210s clocks.

Fix: a bounded queue drained by one writer thread (or the pump's existing best-effort path), counting drops as eventWriteFailures; alternatively move record(reasonEvent) after stream.rebuild().

### 13. giveUp() does not short-circuit the rest of sweep()
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:186`

giveUp() latches gaveUp/running but sweep() only checks gaveUp at entry, so a give-up reached inside the drain lets the same pass keep issuing Retry resubscribes, re-run the budget check (a second giveUp → second FEED_DEAD and second onExhausted.run(), since giveUp has no idempotence guard) and run checkStaleness, which can write WATCHDOG_STALE and resubscribe after FEED_DEAD.

Scenario: queue holds [SubscriptionError(DAX) #3 with no witness, SubscriptionError(NASDAQ) #1]. Rebuild → backoff.exhausted → giveUp('ceiling') → FEED_DEAD + exit thread spawned. The loop continues: NASDAQ → Retry → stream.resubscribe on a stream that is still live; line 186 may fire giveUp('budget') again; line 194 checkStaleness → RESUBSCRIBE remedy → WATCHDOG_STALE row after FEED_DEAD.

Fix: `if (gaveUp) return;` after each stage and an idempotence guard in giveUp(); the existing 'never re-announced' test only covers the next sweep's entry check.

### 14. Watchdog RESUBSCRIBE skips witness.onSubscribeStarted
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:229`

The watchdog RESUBSCRIBE remedy calls stream.resubscribe(epic) without witness.onSubscribeStarted(epic, now), unlike the Retry path (line 241) and the rebuild path (line 345), so the witness keeps confirmed={PRICE,CHART} for a market whose legs were just torn down and are unconfirmed; it can serve as a fully-subscribed witness and quarantine another market on stale evidence, and never gets the fresh confirm window §3.5 promises.

Scenario: X and Y confirmed. X goes tick-silent → RESUBSCRIBE → X's pair replaced; WitnessQuarantine.markets[X].confirmed still has size 2. The session is sick: X's new pair is rejected or hangs while X's flag reads CLOSED, then Y's watchdog resubscribe is rejected three times → WitnessQuarantine.java:72 sees X.confirmed.size()==2 → Quarantine(Y): a session-shaped failure isolated as market-shaped.

Fix: call witness.onSubscribeStarted before stream.resubscribe in applyRemedy.

### 15. Half-dropped pair wedges surgical recovery; fake blind (PLAUSIBLE)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/IgStreamControl.java:193`

dropLegs unsubscribes price then chart and only removes the pair when both succeed, so a chart-unsubscribe throw leaves an already-inactive price handle in legs that every later resubscribe/quarantine re-passes to client.unsubscribe first, which per the Lightstreamer SDK contract throws IllegalStateException for an inactive Subscription; FakeStreamTransport.unsubscribe refuses atomically and tolerates re-unsubscribing a removed handle, so the existing test cannot produce or detect this.

Scenario: legs = {DAX → (P, C)}. dropLegs: unsubscribe(P) succeeds, unsubscribe(C) throws → legs.remove skipped. Every later resubscribe(DAX)/quarantine(DAX) → dropLegs → unsubscribe(P) throws before C is retried → 'resubscribe failed' each time; PRICE is gone from the wire with CHART live; two wasted watchdog resubscribes then a whole-session REBUILD at ~T+450s.

Fix: forget each leg as soon as its own unsubscribe succeeds (or catch per leg and continue), and give the fake per-handle state and a per-leg refusal knob.

### 16. resubscribe forgets old pair before new pair is complete
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/IgStreamControl.java:139`

resubscribe() forgets the old pair (dropLegs removes the map entry) before subscribePair runs, and subscribePair subscribes PRICE then CHART with no rollback, so a CHART-subscribe throw leaves a live PRICE handle that legs no longer knows about; the next resubscribe double-subscribes PRICE and quarantine can never unsubscribe the orphan.

Scenario: resubscribe(DAX): dropLegs unsubscribes both and removes the entry; subscribePrice returns live handle H1; subscribeChart1m throws → caught at line 140, legs.put never runs. Next resubscribe(DAX): dropLegs is a no-op, subscribePair subscribes PRICE again (H2) → every DAX tick delivered twice into Buffers; quarantine(DAX) returns true while H1 keeps streaming. Trigger is narrow on the real SDK (synchronous argument/state exceptions only) and FakeStreamTransport.subscribe cannot throw.

Fix: subscribe the new pair into locals and only replace the legs entry once both succeed, unsubscribing the first new leg if the second throws.

### 17. connect() publishes stream early; start() lacks RuntimeException catch
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/IgStreamControl.java:171`

connect() assigns stream = live before the subscribe loop, so a RuntimeException from subscribePair on market N leaves an open, partially subscribed connection whose CONNECTED callback still resets the ladder while rebuild() logs 'stream left down'; at boot the same throw escapes start(), which catches only IgFatalConfigException and IgRetryableException|IOException (rebuild() has the RuntimeException arm start() lacks), with no closeStream and no FATAL line.

Fix: subscribe into locals and publish stream only after the loop (closing live on failure); add a catch (RuntimeException) in start() mirroring rebuild().

### 18. Escalator keeps old substate after connect() throws
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:336`

After a rebuild whose connect() throws, no new status arrives, so StuckSubstateEscalator keeps the OLD connection's WILL-RETRY substate and rebuildDue is true every sweep; rebuilds are then paced purely by BackoffPolicy (5,5,5,8,16,32,60…) and the 10-rebuild ceiling is reached in roughly four minutes, well inside the 10-minute budget, so an IG-unreachable outage ends with reason=ceiling rather than budget. Chapter 10 describes the budget as 'a clock, not a count' for exactly this outage.

Fix: clear the escalator's substate in rebuild() (the new connection will report its own), or make the ceiling count only rebuilds that reached CONNECTED.

### 19. Uncaught sweep exception: cause-less FATAL up to 60s later
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:151`

Supervisor.run() guards only InterruptedException around the sleep, so a RuntimeException escaping sweep() ends the capture-supervisor thread with no FEED_DEAD event and no onExhausted; Main notices only at its 60s heartbeat poll and exits with 'capture supervisor died' naming no cause, while the comment at lines 366-367 ('never allowed to kill the sweep thread') states the opposite intent.

Fix: catch (RuntimeException) in run() that records FEED_DEAD{reason: fatal, cause} and calls onExhausted — or one die(cause) path installed as UncaughtExceptionHandler on both worker threads, replacing the poll.

### 20. Shutdown tail: one 5k-tick drain, no recover attempt
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/ingest/Pump.java:223`

run()'s tail is a single drainOnce() (tick loop capped at TICK_BATCH=5_000) plus flush(), so with a healthy sink and >5_000 ticks queued at stop() the remainder is discarded with failure==null and no log line (Main's 'not written' line is gated on failure()!=null); and hold()'s `for (attempt = 1; running; …)` makes zero recover() attempts once stop() has landed, so a stop during or just after a blip forfeits bars and the held batch that one recover() would have landed.

Scenario: (a) long hold backlogs 60k ticks; recover() succeeds; the operator restarts a second later: tail drains 5_000, flush, return normally → ~55k ticks vanish with only the generic summary line. (b) Ctrl-C lands while a bar write fails with a one-shot retryable error: hold() loop body never runs, tail drainOnce hits refuseIfBroken, failure set, hook logs the loss — announced, but a single no-wait recover()+drain would have saved D28's 'a restart must not lose' data.

Fix: `while (drainOnce() > 0)` in the tail, one recover() attempt when the sink is broken at stop, and an unconditional pendingWrites() line at shutdown.

### 21. rebuilding() mixes sweep mono with LS-thread wall stamp
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/Supervisor.java:334`

Only the SUBSCRIPTION_REJECTED rebuild passes error.at() (captured on the LS thread) as the wall stamp alongside a sweep-time monotonic stamp into reconnects.rebuilding(), so when no outage is already open the classifier's wall-minus-awake host-sleep discriminator is inflated by queue latency and the RECONNECT row reports hostSleptDuring=true and an inflated wallOutageMs spuriously. Nothing acts on the flag, so the harm is a false breadcrumb.

Fix: pass the SubscriptionError's monotonicNanos (already captured at line 119) or use clock.wallInstant() for rebuilding().

### 22. Heartbeat borrows one connection per epic; N×5s stall when DB down (efficiency)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/HealthProbe.java:48`

publish() calls store.upsert(snapshot(epic)) once per epic and each upsert borrows its own pooled connection and re-prepares the 13-column UPSERT, so during a Postgres outage each borrow waits the full 5s connectionTimeout and the main thread — which is also the pump/supervisor liveness backstop — stalls N×5s per heartbeat.

Fix: one connection and one PreparedStatement, addBatch per epic, one executeBatch (also giving all rows one updated_at_utc), or short-circuit the remaining epics after the first connection-acquire failure in a heartbeat.

### 23. No test combines the detectors the top findings sit between (test coverage)
`market-data-service/src/test/java/dev/amfshr/tradebench/marketdata/supervise/SupervisorTest.java:425`

No scenario combines a DISCONNECTED:* status with tick-silent markets, none rejects both legs of one market in one attempt (or feeds a fourth error after a quarantine), none advances time past a Wait, none feeds a bare DISCONNECTED or onServerError, and FakeStream.rebuild() is synchronous with no queued statuses — exactly the interactions behind findings 1, 2, 4, 5, 8 and 11, which per-component fakes hold constant. Each confirmed finding above is also the exact mutation that would expose the missing test: add the fourth error, the TRYING-RECOVERY status before T+90s, the 31s advance after Wait, the bare 'DISCONNECTED' with CLOSED flags.

### 24. Shutdown hook registered late; chapter 10 overstates the join fence
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/app/Main.java:170`

The shutdown hook is added only after control.start() (up to the 10-minute boot budget), after both worker threads start and after PacerDiscovery.discover(), so a SIGTERM in that window exits with no stream close, no pump drain and no sink.close(); separately, chapter 10 says the hook 'waits for the sweep thread before closing the stream' but the code waits 5s then closes anyway, and the post-timeout path touches legs/stream unsynchronised (benign only because the interrupt reliably breaks the login gate and HTTP waits).

Fix: register the hook before control.start() (it can tolerate null fields), and either align chapter 10's F3 text with the 5s bound or make the hook skip control.close() when the join times out.

### 25. Three field-manual links broken by the D26 restructure (docs)
`docs/design/architecture/components/ig-client.md:26`

ig-client.md:26 links ../../../field-manual/01-sockets-and-lightstreamer.md and core.md:15 and :28 link ../../../field-manual/07-clocks-wall-monotonic-and-sleep.md, flat paths that the D26 restructure (inside this PR range) moved under market-data-service/ and foundations/; none resolve on disk and no other field-manual link in docs/ is broken. On the docs site, web/docs/src/links.ts:57-60 falls back to a GitHub blob URL, so the reader lands on a 404.

Fix: the paths to market-data-service/01-… and foundations/07-…, and a real dead-link check over the tree (links.test.ts currently simulates exists()).

### 26. Best-effort event write copied 6×, with divergent policy (reuse)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/PacerDiscovery.java:92`

The 'try { events.write(..) } catch (RuntimeException) { count }' block appears six times (Pump.java:110-121, 124-131, 203-213, 236-244; Supervisor.java:367-373; PacerDiscovery.java:92-98) plus the same shape for store.upsert in HealthProbe, and the copies already disagree: PacerDiscovery logs but keeps no counter, so its lost events never reach Main.summary(), contradicting its own comment at line 83.

Fix: a CountingEventLog decorator implementing EventLog (delegate + AtomicLong + optional log consumer) wrapped once per producer in Main; Pump.detectGap keeps its own try because it also guards gapDetector/gaps.record.

### 27. Four hand-rolled FakeClocks and three RecordingEventLogs (reuse)
`market-data-service/src/test/java/dev/amfshr/tradebench/marketdata/supervise/SupervisorTest.java:631`

SupervisorTest.FakeClock (advance + advanceWallOnly), PumpTest.FakeClock + FakeSleeper (advance + runaway guard), and byte-identical anonymous FROZEN clocks in HealthProbeTest and PacerDiscoveryTest reimplement core.time.Clock; PacerDiscoveryTest.RecordingEvents and SupervisorTest.RecordingEvents are byte-identical and PumpTest.FakeEventLog is the same minus failWrites; ig-client's FakeTime is not a Clock and lives in src/test, unreachable from market-data-service.

Fix: one FakeClock (advance, advanceWallOnly, sleeper()) and one RecordingEventLog in a market-data-service test util (or core testFixtures); all existing assertions unchanged.

### 28. FlakySink drops a failed tick; production holds it (test fidelity)
`market-data-service/src/test/java/dev/amfshr/tradebench/marketdata/ingest/PumpTest.java:348`

FlakySink.write(Tick) calls failIfArmed() before recording the tick, so the fake discards a failed tick, whereas PostgresStore.write(Tick) appends to pending before anything can fail and re-binds it in recover(); aTickWriteBlipHoldsAndTheStreamResumes therefore asserts List.of("tick@11") and cannot distinguish 'held and re-sent' from 'lost'. The no-loss property is covered at the store boundary, so this is a fidelity nit.

Fix: let the fake hold the failed tick and replay it in recover(), and assert List.of("tick@10","tick@11").

### 29. closing() hush is dead code and a cross-thread data race (simplification)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/supervise/ReconnectClassifier.java:37`

closing()/GRACEFUL_CLOSE is unobservable in production: IgStreamControl.closeStream() bumps the generation (line 251) before live.close() (line 257) and gated() drops every stale-generation status, and Main's hook calls closing() → stop() → interrupt → join → control.close() so the sweep thread is stopped before a farewell could arrive; meanwhile `closing` is a plain non-volatile boolean written on the hook thread and read on the sweep thread.

Fix: delete Supervisor.closing(), ReconnectClassifier.closing()/GRACEFUL_CLOSE and the two tests with a note that the generation gate is the §3.6 hush, or keep it and make the field volatile.

### 30. Null-bearing brokenBy lacks @Nullable under @NullMarked (conventions)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/store/PostgresStore.java:63`

brokenBy is assigned null in recover() and null-checked in refuseIfBroken(), so null is its meaning ('not broken'), yet it is declared without @Nullable inside the @NullMarked store package — the one load-bearing nullable in the hold-and-retry state machine is the one D14 says must be annotated. Only unannotated null-bearing field across the 47 changed main Java files.

Fix: `private @Nullable SQLException brokenBy;` (and drop the double space).

### 31. epicOf re-parses an item-name format built one module away (altitude)
`market-data-service/src/main/java/dev/amfshr/tradebench/marketdata/ingest/Buffers.java:106`

Buffers.epicOf (new in this PR, used by onMalformed) string-splits Lightstreamer item names that ig-client's IgStreamSession builds at lines 33 and 51, whose update lambdas already capture `epic`, so StreamEvents.onMalformed could carry the epic and the parser plus its tests could be deleted. Today a parse miss is counted globally but attributed to nobody, so capture_status.malformed under-reports.

Fix: onMalformed(String epic, String itemName); implementors are Buffers plus three test fakes.

### 32. Bare build/, out/, tmp/ still swallow src packages (conventions)
`.gitignore:18`

git check-ignore confirms .gitignore:18 `build/` ignores market-data-service/src/main/java/dev/amfshr/tradebench/build/X.java, and `out/` (:20) and `tmp/` (:40) behave the same, while the PR's own comment at :26 names exactly this hazard and scoped only dist/ and coverage/.

Fix: append `!**/src/**/build/`, `!**/src/**/out/`, `!**/src/**/tmp/` (a `/build/` anchor would not work for a multi-module Gradle tree); or a CI step running git check-ignore over */src/** and failing on any hit.
