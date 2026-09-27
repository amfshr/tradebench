---
name: start-ticket
description: Start build work on a board ticket — scope check, branch, doctrine-bound implementation, PR restating the DoD. Use when Alex picks a ticket (e.g. "start E1-T2").
---

# Start-ticket protocol

1. **Read before code:** the ticket, its epic's mission and references, **its plan section in
   `.claude/tasks/epics/e<n>-<slug>.md`** (refine the plan there if reality has moved — it's a
   living sketch), and the relevant PRD sections. The board (`.claude/tasks/board.md`) is the
   single source of truth (G3).
2. **Scope check.** If the ticket hides an unmade decision, surface it as `Decision: (TBD)`
   and stop — never implement past it. No scope adds: new work earns its own board line, not
   a place on this branch.
3. **Branch** `e<epic>-t<ticket>-<slug>` (e.g. `e1-t2-ig-session-core`); mark the ticket 🔶
   on the board.
4. **Build to convention:** small, self-contained increments; injectable clocks; user +
   data-source dimensions in every schema/API; cheap work only on transport callbacks; config
   discipline (no machine-specific code defaults; secrets in env beside config, never
   committed — G1).
5. **Test to doctrine (G5):** every behavioural test mutation-verified — apply the exact
   break it exists to catch, see red, revert; anchor expectations independently of the code
   under test; golden bytes for wire contracts; failure-mode tests for fail-closed paths;
   exact tests on boundary equalities.
6. **Before the PR:** run the `doctrine-reviewer` agent on the diff; fix findings or record
   why not.
7. **PR:** restate the ticket's DoD as a checklist with evidence per item; commits styled
   `<area>: <emoji> <summary>`; CI green. **Alex merges** (framework §1) — never self-merge.
8. **Close out:** ticket status updated, Done-history line with its evidence (PR link), and
   the next-session menu refreshed.
