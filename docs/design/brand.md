# Tradebench brand & visual identity

Ruled 2026-09-29 (design session
[2026-09-29-brand-and-visual-identity](sessions/2026-09-29-brand-and-visual-identity.md),
R1–R11; decision D19). Evidence: Alex's design-tool exploration export in
[`research/2026-09-29-brand-explorations/`](research/2026-09-29-brand-explorations/README.md)
— a ten-turn log with its own decision record (D-01…D-09), ratified as rulings. This spec
asserts *what is*; the session record holds *why*.

## Direction — Quiet Terminal (R1)

Dark-first, market-adjacent restraint: near-black ink, one cool blue for action, data left
to speak for itself. Its neutrals sit beside red/green candles without competing. Light
mode is "the same terminal by daylight": cool grey paper, ink-dark text, the blue deepened
to hold contrast. Roads not taken (recorded, closed): The Workbench (brass collided with
amber attention states), Paper & Ink (read as a newspaper; serif rejected).

## Tokens (R2, R7, R8)

| token | dark | light |
|---|---|---|
| bg | `#12161D` | `#F7F8FA` |
| surface | `#1A2029` | `#FFFFFF` |
| text | `#E6EAF0` | `#12161D` |
| muted | `#8F98A8` | `#5B6472` |
| accent | `#5B9CF6` | `#1F62D0` |
| border | `#2A3240` | `#DCE0E6` |
| attention | `#E0B04F` | `#8F5A0B` |
| up | `#3FB68B` | `#1F8A5B` |
| down | `#E5534B` | `#D13B3B` |

All text-role pairs pass WCAG AA in both modes (audit in the session record). Dark bg is
deliberately one step lifted off the tool's original `#0B0E14` ("felt like a void").

## Type (R3)

**Inter** carries reading (body, headings). **JetBrains Mono** carries data, code, labels,
ids, timestamps, and the wordmark. Both self-hosted. No serif; Newsreader was seen and
rejected.

## Wordmark & mark (R4, R5)

Wordmark: `tradebench▊` — JetBrains Mono 500, lowercase, an accent-colored cursor block
after the name (a drawn block, not a glyph). Mark/favicon: **two candles** — a filled +
hollow pair, accent on the hollow one; reads as trading without the name at 16px.
Rejected marks (closed): bench profile, sealed/conventional candle, healed gap, event
tick, lone cursor block.

## Component rules

- **Sidebar active state (R6):** tree rail — hairline rail on nested levels; the active
  line lights its segment with a 2px accent edge + accent text. No filled pills.
- **Attention is amber, and rare (R7):** red and green are reserved (below), so blocked/
  halted/stale states use amber — **at most once per screen**. Quiet is healthy (P9): if
  everything is amber, nothing is.
- **Market colours are sacred (R8 — standing platform doctrine):** the up/down pair and
  P&L are the ONLY uses of green/red anywhere in the UI. Strategy overlays, entries/exits,
  and annotations use the brand accent — *the market and the strategy never share a
  colour*. This binds all future chart UI (E4+).
- **Scar callouts (R9):** surface card with a vertical mono `SCAR` spine, title, dated
  cost, story, and a **Lesson.** line. No icon, no colour — failure stories get dignity,
  not warning triangles.

## Guardrails (R10)

Light-mode up/down as **text** measure 4.08:1 / 4.49:1 — AA-large only. P&L text in light
mode renders semibold at large sizes, or uses darkened variants when small. Candles and
other non-text graphics are unaffected (3:1 non-text requirement met).

## Scope notes (R11)

The exploration's mock *content* (markets, tickets, chapter titles, DSL snippets) is
illustrative fiction — the visual system is ratified, none of the content is. The
exploration's epic-page and ticket-page layouts are adopted as **T2 design intent**
(board page + epic pages); ticket-detail pages ride later tickets.
