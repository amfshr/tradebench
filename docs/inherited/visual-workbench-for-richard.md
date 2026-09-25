# Visual research workbench — does this match your idea?

**For: Richard. From: Alex (+ notes worked through with Claude).**

## Why you're reading this

I'm sketching the design for the long-term Java trading platform (the proper rebuild, after the Python
prototype). Your idea about a **visual chart tool where you skip through data fast and then step slowly
over the interesting bits, experimenting with indicators** became one of the cornerstones — but I want
to make sure we've captured *your* version of it, not a version we drifted into.

**Please:** read the summary, tell us where it's wrong, and answer the questions at the end (scribble
inline — rough answers are fine). The whole point is to check we're building the thing you actually have
in your head.

---

## In one paragraph — what we think you mean

A tool where you load a chunk of historical market data onto a chart and **move through time at will**:
jump quickly over the boring stretches, then slow right down and step **bar-by-bar** (or even
**tick-by-tick**) through a region you find interesting — all while **adding, removing and tweaking
indicators live** and watching them recompute. It's a **research / experimentation** tool: you're using
your eyes to understand price behaviour, develop and calibrate indicators, and form strategy ideas —
*before* those ideas get written up as a formal, testable strategy.

## The tool, as we picture it

- Load a day / range of historical data for a market (e.g. DAX).
- **VCR-style controls:** play, pause, step forward/back one bar, jump (seek) to any point, fast-forward.
- **Skip fast, step slow** — scan quickly to a region you like, then crawl through it.
- **Experiment with indicators** — drop indicators on, change their parameters, see them redraw instantly.
- (Maybe) overlay what a candidate strategy's rules *would have done* — just to see it on the chart.
- (Maybe) mark/annotate interesting moments to come back to.

## How we think this differs from "backtesting"

This is the distinction that unlocked it for us. We think there are **two different tools** that happen
to share the same guts (the same data + the same indicator maths):

| | **Visual workbench** (your idea) | **Headless backtester** |
|---|---|---|
| Who's driving | **you**, scrubbing a chart | a program, running on its own |
| Needs a finished strategy? | no — you're exploring | yes — that's what it tests |
| How it moves through time | skip fast, step slow, play | just runs start → finish |
| "Skipping ahead"? | the whole point | makes no sense — it just runs the lot |
| What you get out | insight, tuned indicators, ideas | a trade log + performance numbers |

The key thing we landed on: **"skip fast / step slow" only makes sense when a *human* is looking.** For
an automated backtest of a finished strategy with no screen, there's nothing to skip — you just run the
whole day. So the skipping lives in *your* tool, and the two tools share the data and the indicator code
underneath. **Does that split match how you see it, or do you see it as one tool?**

## The one technical thing we want to sanity-check

Indicators are **cumulative** — the value of something like a moving average at 2pm depends on *every*
bar before it that day. So you can't literally "skip" data and still have correct indicators.

The way we think it works: when you **jump to a point**, the tool quietly **calculates all the bars up
to there** so the chart and indicators are correct at the point you land on. In other words, **you're
skipping the *looking*, not the *maths*** — the maths still runs for every bar; you just don't stop to
watch. (We think this is exactly your "batch-calculate everything up to a certain point" mode, vs the
"click through each data point one at a time" mode. **Have we understood your two modes right?**)

---

## Questions for you

### A. Purpose & how you'd use it

1. Is this mainly for **research / developing indicators / forming ideas by eye** — or do you also
   picture it **running and checking finished strategies**? (We've assumed the first.)
2. **Historical only**, or do you also want to point it at **live** data and watch it move?
3. Your "two modes" — *step through each point* vs *batch-calculate to a point* — did we capture them
   right? Is "batch to a point" the same as "jump the playhead and everything's correct there"?
4. Standalone tool, or part of the bigger platform (sharing the same data store and indicator library)?
   We're leaning **share everything underneath** so what you see on the chart is *exactly* the maths a
   real strategy would use — no "looked great on the chart, behaved differently live" surprises.
   Agree?
5. What does a good session look like for you day-to-day? Walk us through the workflow you imagine.

### B. Timeframes — this is the bit we most want your steer on

6. What timeframe(s) do you picture working on — **1-minute, higher (10-min / hourly), ticks**? All of
   them?
7. Do you want to **switch timeframe on the fly** — e.g. view the whole day on an hourly chart, spot
   something, then zoom into 1-minute (or ticks) on just that region?
8. When you "step slowly," what's **one step** — one bar of whatever timeframe you're viewing? And can
   you **drill down to tick-by-tick** *inside* a bar when you want to?
9. As you step, do you want to **watch a higher-timeframe bar building** (e.g. see the 1-hour bar grow
   minute by minute), or only ever see **completed** bars?

### C. The tricky one — jumping when things are defined on different timeframes

10. Say you've got **two indicators/strategies on different timeframes at once** — one on 1-minute, one
    on 1-hour — and you **jump forward**. When you land, should **all** timeframes be correct at that
    exact point? (We assume yes.)
11. **How big is a "jump"?** Do you want to jump by the *strategy's own bar* (e.g. hop forward one
    10-minute bar at a time), or jump **freely by clock time** (e.g. "go to 13:45") and let every
    timeframe just recompute to there?
12. When several timeframes are on screen, what should **the smallest step be** — the **finest**
    timeframe present (so a step never skips a 1-minute bar if a 1-minute thing is on the chart)? Or
    should each timeframe step independently?
13. If a strategy is defined on, say, **10-minute bars**, does it only make sense to *stop and look* on
    completed 10-minute boundaries — or do you want to freeze mid-bar and inspect the half-formed bar
    plus the finer data underneath it?

### D. Adding & recalculating indicators on the fly

A big part of the tool is **adding or changing an indicator mid-session**. If you drop an RSI on the
chart at 1pm, it can't just appear from 1pm — it has to calculate **from the start of the day** (or from
however far back it needs) and **fill in its whole line** up to now. Same when you change a parameter:
the whole line redraws.

To make that reliable, Alex wants every indicator built to a **clear, common shape** — each one spells
out:
- **how it starts** — its initial/seed value, and how much history it needs before it's valid (its
  "warm-up");
- **how it updates on each new bar** — its step rule (usually "new value from the previous value + this
  bar");
- and, where possible, **how to compute a whole stretch at once** (in bulk, fast — "vectorised").

That way the tool always knows how to (a) fill an indicator's line from scratch when you add it, (b) nudge
it one bar forward when you step, and (c) get the **same answer** either way.

14. Does that match how you think about indicators — each one having a clear "starting value + a rule for
    each next bar"? Or do you picture them more loosely as "a formula over the whole window"?
15. Are there indicators you use that **don't** fit a simple "previous value + this bar" rule — e.g. ones
    that need the whole window recomputed, a rolling highest-high / lowest-low over the last N bars, or
    something anchored to the session open? **Concrete examples really help** — they change how we build
    the engine.
16. Do your indicators ever **build on other indicators** (e.g. a smoothing *of* another indicator, or a
    signal line off a MACD)? That tells us whether indicators need to feed into each other.
17. When you **add or re-parameterise** an indicator mid-session, what do you expect it to feel like —
    the whole line redrawing **instantly**? Any lag you'd accept on a long range?
18. Do you care whether the maths runs **all at once** (bulk/fast) vs **one bar at a time** (as you step)
    — as long as they always give **identical** results? (We plan to guarantee they match.)

### E. Scope & references

19. Roughly how much data at once — a **day, a week, months**?
20. Any existing tools that do this the way you like (TradingView's bar-replay, something else)? What do
    they get right, and what would you want *better*?

---

## If we've got it wrong

No problem — that's what this doc is for. Tell us where the picture diverges from yours, especially on
purpose, the timeframe behaviour (section C), and how indicators get calculated (section D). We'd rather
redraw it now than build the wrong thing.
