---
name: design-session
description: Run a Tradebench design deep-dive with Alex — ground in the seed pack, survey standard approaches, strawman, structured questions, then file the answered record and log decisions. Use for any session meant to settle design questions (PRD open Qs, epic gates).
---

# Design session protocol

The pattern is the project's own calibration-truth machinery (playbook §5.2: question sheets
→ answered records). **Claude proposes, Alex rules.**

1. **Ground first.** Search `docs/inherited/` (use the `seed-pack-librarian` agent), prior
   `docs/design-sessions/` records, and `docs/decisions.md` for everything already settled.
   Never re-litigate silently (G2).
2. **Bring the lesson.** Survey how the industry / standard practice handles the problem —
   name the paradigms, what each got right, and their known failure modes. Alex's domain
   knowledge may be weak in places and he says so: teach properly, don't assume.
3. **Strawman.** One concrete recommendation with illustrative sketches, explicitly marked
   non-committed. A recommendation to react to beats an options survey.
4. **Structured questions.** Pointed, numbered, each with a recommendation; free-form answers
   welcome. Push back where Alex's instinct conflicts with evidence — he wants the argument,
   not deference; his ruling ends it.
5. **File the record — same day**, at `docs/design-sessions/YYYY-MM-DD-<topic>.md`:
   status header (OPEN/CLOSED, what it resolves/spawns) · the survey condensed · numbered
   rulings (R1…) each with the *why* and who ruled · illustrative sketches (marked
   non-committed) · spawned work · PRD open-question register effects.
6. **Land the satellites:** `docs/decisions.md` entries for real decisions (D-numbers, why,
   status) · board touch (gates, menu, Done history) · commit docs-only direct to main (the
   G4 exception), style `docs: <emoji> <summary>`.

**G1 applies absolutely:** no real strategy content (Pattern 1, range-bar family) in any
record — acceptance-anchor checks run off-repo, only verdicts are recorded.
