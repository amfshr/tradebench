# The DAX's hours on IG (`IX.D.DAX.DAILY.IP`)

> **Observed, not published.** IG returns no opening hours for this epic (`openingHours: null`, D25). These hours are
> read from the collector's own archive — every day from 2026-10-16 to 2026-11-21, ticks and one-minute bars — in the
> one-month review (`docs/design/sessions/2026-11-22-the-one-month-review.md`). The collector does not use them: it
> streams 24/5 and takes "open" from IG's dealing flags (D25, D35). They are here for strategy authors and for
> reading the data.

## The week runs on New York time

| | New York | UTC, US summer time | UTC, US winter time |
|---|---|---|---|
| **Sunday open** | 18:00 | 22:00 | 23:00 |
| **Friday close** | 17:00 | 21:00 | 22:00 |
| **The thin hour, every weekday** | 17:00–18:00 | 21:00–22:00 | 22:00–23:00 |

The hours move with the **US** clock change, not the EU one: on 2026-10-25 (the EU change) the Sunday open stayed at
22:02 UTC; on 2026-11-01 (the US change) it moved to 23:02.

## Inside the weekday

- **Around the clock, Monday to Friday.** No nightly close: on most weekdays every minute has a bar.
- **The thin hour** (17:00–18:00 New York) is the US index futures' daily break: about 250–500 ticks in it, against
  9,000–12,000 in a busy hour. Minutes with no trade at all fall here; the daily heal finds IG has no candles for them
  either (`tickless_at_source`).
- **Overnight** (Asian hours) about 5,000–7,000 ticks an hour, 50–65% of seconds carrying a tick.
- **The peaks:** 08:00 UTC (09:00 Frankfurt, the Xetra open) — ~12,700 ticks an hour, 96% of seconds with a tick —
  and the US cash open (09:30 New York — 14:30 UTC in US winter; 14:00–16:00 UTC ran ~11,000 ticks an hour in
  November).

## Reading it

- A tick is a conflated price snapshot (Lightstreamer MERGE mode), not every trade; up to 11 arrived in one second.
- One-second bars can be built from the ticks; a second with no tick had no price change IG sent (D40).
- These are IG's demo prices for its daily-funded DAX, not Xetra's cash index; the hours are IG's market, not the
  exchange's.
