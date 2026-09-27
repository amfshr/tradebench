---
name: sanity-lap
description: Cold-start protocol — read CLAUDE.md → PRD → board → decision log, then play back the project state and what's next. Run when a session starts cold, context is stale, or Alex asks "where are we".
---

# Sanity lap

The point of this lap is double: re-arm the session with current truth, and **prove the
handoff is self-sufficient** — any gap you hit is itself a finding.

1. **Read, in order:** `CLAUDE.md` → `docs/inherited/product-requirements.md` (the PRD) →
   `.claude/tasks/board.md` → `docs/decisions.md` → the most recent record in
   `docs/design-sessions/` (if any).
2. **Play back, leading with the outcome:**
   - *What the project is* — one tight section; cite principles by number (P1–P10).
   - *Where it stands* — epic statuses, decisions to date (D-numbers), Done history, anything
     in flight.
   - *What's next* — the board's next-session menu verbatim, plus any gates/waits that bound
     the choices.
3. **Report contradictions and staleness** between the docs (board vs decisions vs CLAUDE.md)
   — that's the lap's real product. A clean lap says so explicitly.
4. **Stop and let Alex pick.** The lap never starts work uninvited.
