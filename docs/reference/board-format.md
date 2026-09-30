# Board & ticket format

The formal structure of `.claude/tasks/board.md` and the epic plan files in
`.claude/tasks/epics/`. Markdown is the write model; the docs site (`web/docs`) parses this
structure into the board dashboard, epic pages, and ticket-detail pages. Keeping to the
format is what lets the read model render; the **`board-steward` agent** owns authoring and
maintaining it. Ruled: decision D20.

**Design principle:** lean, relevant fields — information that serves a decision, not data
for its own sake (Alex, 2026-09-30). A field earns its place by being something you'd
actually look up: what type of work, which branch, when it started, what blocks it.

## The board — `.claude/tasks/board.md`

- `# Tradebench Board` — the title (parsed as the board name).
- Intro blockquote + `**Guide (…):**` + `**Next-session menu (…):**` — prose, not parsed
  as structure.
- One `## E<n> <emoji> <name> — <STATUS-LABEL> (<note>)` header per epic.
- Directly under each epic header, the epic metadata line:
  `**Since** <YYYY-MM-DD> · **Decisions** <D2, D17, …> · **Book** <ch. 1–9 | —>`
- `**Mission:** <one line>` — the epic's mission.
- A ticket table: `| # | Ticket | Status |` with rows
  `| T<n> | **<Title.>** <summary> **DoD:** <done-when> | <emoji> <note> |`.
- `## Done history` — dated bullets `- **<YYYY-MM-DD> — <title>** …`; entries tagged
  `E<n>-T<m>` surface on that epic's page.

**Status emoji (legend):** ⬜ queued · 🔶 in progress · ✅ done · 🧊 iced/blocked. The
**leading** emoji of a status cell is the status; a note may mention others.

## The epic plan file — `.claude/tasks/epics/e<n>-<slug>.md`

Per-ticket rich detail. Each ticket has a section:

```
## T<n> — <Title> <status emoji + short state>

**Type** <build | test | design | docs | ops | spike> · **Branch** `<git-branch>` · **Started** <YYYY-MM-DD | —> · **Blocked by** <T<m> | E<n>-T<m> | —>

<approach, build order, test plan, DoD — free markdown; the ticket-detail page renders it>
```

**The metadata line is a single physical line** — the parser reads one line only; a wrapped
second line (e.g. starting `**Started**`) would fall into the plan body instead.

The **ticket metadata line** is the new template field set (D20). All fields are optional
and degrade gracefully — a missing field simply doesn't render. Field meanings:

| Field | Meaning |
|---|---|
| **Type** | the kind of work — `build` (feature), `test`, `design` (a nod/session), `docs`, `ops` (deploy/infra), `spike` (investigation) |
| **Branch** | the git branch the work lives on (backtick-wrapped) |
| **Started** | when work began (the board row's note carries the *done* date / PR) |
| **Blocked by** | ticket(s) that must land first, or `—` |

Epic and ticket **id + title + status + DoD** always come from the board row (the index);
the epic-file section adds the metadata line and the rich plan body. A ticket with no
epic-file section still renders from its board row alone.

## Retrofit status

Not all existing tickets carry the metadata line yet (the template postdates them). The
`board-steward` agent fills them in over time; the site renders whatever is present. New
tickets should follow the template from creation.
