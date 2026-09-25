# Grill Session 2 — The Product Surface

> **What this is.** The answered record of the second product grill session with Alex
> (2026-09-24) — concrete user journeys, screen by screen. This is the primary source for the
> platform's epic list. Companion to [session-1-vision.md](./session-1-vision.md).

**Status: CLOSED.** Remaining unknowns are recorded as design-opens (they're design work, not
missing product intent).

---

## J1 — First run / onboarding

- **Onboarding wizard** (one-off phase): recommended initial setup — broker credentials → market
  data stream jobs (times/cron, dates) → config.
- **Data installer/uploader tool**: user-provided historical data can be uploaded if it meets a
  specific schema, in whatever format options are appropriate — DB files, a stream connection,
  Parquet files. Point: take existing data a user already has and make it available in the
  platform under that user.
- **Post-upload analysis dashboard**: a summary/analysis view of freshly uploaded data.
- **Gap detection + gap fetcher**: flagged as interesting — a **broker-dependent service a user
  could optionally run separately**.
- ⭐ "Something that would be **very very nice**: streaming live the current market they select
  and being able to view **candlestick charts, or range bars**" — a live chart view, independent
  of backtesting. (Note: range bars as a first-class bar type — inherited from Dad's range-bar
  model world — has real architectural implications for the bar/series model.)
- **Platform intro wizard** explaining the sub-platforms (likely subdomains): the **market data
  manager** (upload/view/manage/export stored data), the **charts/backtester** view, and the
  **config/jobs/traderbot** platform. Plus a **DSL teaching wizard** — lessons + default/example
  strategies.
- Alex's own reaction: "this is making me think this is so massive scope, but it's what you asked
  for — the sky is the limit with this kind of project."

## J2 — The indicator-development evening

- **In-browser DSL code editor**; work saved under the **user's namespace**, optionally made
  **public to other platform users** (user's preference), stored with strategies; **clonable,
  iterated, versioned**; **everything exportable whenever**.
- ⭐ **The DSL copilot**: "what could be a really nice idea is having a built, trained agent that
  knows and understands the DSL and can write it on prompt for the user." (New product idea —
  nowhere in the prior workspace.)

## J3 — Indicator vs strategy layers

- Open to **separate DSLs** (indicator maths vs strategy rules) if that makes strategy definition
  easier — "happy with either", decided later on design merit. Wants: a catalogue of *available
  indicators* + a clear DSL for *when to apply them* in a strategy. Self-assessed weak on this
  area at this point.
- Strategy defines the **holistic whole**: entry/exit, internal stop loss, profit targets.
- ⭐⭐ **Conviction (scar tissue from the prototype): "I don't want to rely on the provider
  mechanisms to manage trades — I want my internal platform to do it."** Trades were rejected at
  IG because of dealing limits etc. Start with all trade-management concepts applied
  **internally** (platform watches and acts); later, optionally expose provider order types, with
  robustness designed around trailing stops and broker limits. (Direct descendant of the
  prototype's D46 constraint-failsafe principle — carry it forward as a foundation.)
- Two chart modes wanted: **exploration** (tick through data, see indicators evolve, see trigger
  moments — indicator breaks / hits a value) and **strategy replay** (full entry/exit/stop-loss
  lifecycle replayed on the chart — "how a live trade would have looked").

## J4 — The results morning

- Above the fold: **profit/loss, equity curve**, and — notably — **a Monte Carlo run of the
  trade results**. Beyond that, defers to domain guidance: "the more the merrier."
- ⭐ **Trade → replay jump**: from a specific trade in the results, load the data and see the
  chart replay of that moment — "a very very interesting and nice feature."

## J5 — Visual replay mechanics

- **Both modes** (data/indicator exploration AND strategy replay).
- **Scrub backwards: yes for indicator exploration; not needed for strategy replay** (forward-only
  fine at this stage).

## J6 — Promotion to bot

- Setup: user defines/references the **strategy by name** and sets the bot up.
- **Analytics dashboard**: daily/weekly/monthly results, **scoped per any strategy in the DB that
  has traded on demo or live** — one analytics surface across demo and live history.
- **Push notifications on trade entry/exit** etc.

## J7 — Risk pages

- Defers on domain detail, but confirms the shape: **each bot/running strategy has a local limit
  with a kill switch, plus a running global one.**

## J8 — Data ops

- "Yes in all": stream-job controls (start/stop/add markets), health dashboard (last-tick age,
  gaps, reconnects), tiered notifications (email for serious failures like API-key rejection;
  dashboard warnings for the rest).

---

## Alex's two closing worries (recorded verbatim in spirit)

1. **Scope:** "so feature rich and carries a lot of complexity, I'm not sure if we've gone too
   far — it would reasonably take years to build something capable of all of the above… but I
   don't see limitations on the project if it's technically possible."
2. **Domain competence:** "what I worry is that I don't have the domain and understanding of
   strategy definitions to carry out the work in making a system flexible, particularly the
   charts and math mechanics behind strategies."

**Handling (agreed direction):** the PRD holds the full three-year vision AND a ruthless phasing —
a thin spine built end-to-end first, everything else staged behind it. The domain worry is
mitigated by anchoring the DSL/charting design to the two real strategies already fully specified
in the prototype era (Pattern 1, calibrated via Dad's answered sheet; the range-bar model family,
consolidated spec) rather than designing for abstract flexibility.

## Design-opens harvested from this session (design work, not product gaps)

- One DSL or two (indicator layer vs strategy layer)?
- Upload-schema definition for user-provided historical data (formats, validation, mapping).
- Range bars as a first-class bar type alongside time bars — series-model implications.
- Monte-carlo / analytics metric set (domain guidance needed — Alex says "more the merrier").
- Versioning semantics when a strategy references a shared indicator (pinning — see Session 3).
- Brother's concrete persona/role (carried over from Session 1).
