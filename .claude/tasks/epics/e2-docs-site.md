# E2 🧰 The docs site — ticket plans

**Mission:** the human read model — and the platform's ONE docs site (D18): `web/docs`,
served at `docs.tradebench.amfshr.dev`, housing all static documentation (product, book,
architecture, decisions, the board section; later the DSL reference and REST API/
integration docs). Markdown stays the write model (agents and Alex edit
files; git stays the history); this small React/TS app renders the board *and* the docs
tree — the product overview, the Field Manual, design specs — as pleasant pages (D11; scope widened
2026-09-28 to cover `docs/` as Alex's "online book of pages"). It is also the frontend
warm-up: first exercise of the `web/` toolchain at low stakes, deliberately separate
from the future product SPA so warm-up choices bind nothing (tech-notes §6).

**References:** D11 (read model shape) · tech-notes §6 (provisional workspace structure,
confirmed in principle) · `docs/README.md` (the content tree it renders) · PRD §9
(AI-workable NFR — the board/docs being pleasant to read is part of the loop) ·
board E2 section.

**Working shape:** git worktree at `.claude/worktrees/e2-docs-reader`, branch
`e2-t1-docs-reader`, based on main + the four docs-only commits from `e1-t5-resilience`
(cherry-picked; identical patches merge cleanly whichever PR lands first). Runs alongside
E1-T5 without touching it.

---

## T1 — Docs & board reader v0 ✅

**Type** build · **Branch** `e2-t1-docs-reader` · **Started** 2026-09-28 · **Blocked by** —

**Goal:** `npm run dev` serves the repo's markdown as a reading experience: sidebar
navigation over `docs/` + the board, GFM tables, **rendered mermaid**, highlighted code,
working internal links, light/dark, typography that makes the Field Manual a pleasure.

**Design nod (D13) — Claude proposes, Alex rules:**

- **N1 — Toolchain & workspace.** Vite + React 19 + TypeScript (strict) on Node LTS;
  npm (no pnpm/yarn — fewest moving parts). Born as tech-notes §6's shape from day one:
  `web/` = npm-workspaces root, `web/docs/` = this app (named for its subdomain — D18); `platform/`
  and `shared/` are **not** created until they earn existence. *Nod: rec (Alex, 2026-09-28)*
- **N2 — Content pipeline: no backend.** The dev server *is* the file access:
  `import.meta.glob` over the repo's `docs/**/*.md`, `*.md` roots, and
  `.claude/tasks/**/*.md`, loaded raw (Vite `server.fs.allow` up to the repo root).
  Editing a doc hot-reloads the page — the write-model/read-model loop with zero
  infrastructure. A static `build` snapshots the same content (T3). *Nod: rec (Alex, 2026-09-28)*
- **N3 — Markdown pipeline.** `react-markdown` + `remark-gfm` (tables/strikethrough) +
  `rehype-slug`/autolink (anchor headings); mermaid fences rendered client-side by a
  lazy-loaded `mermaid` component; code fences via `rehype-highlight` (highlight.js —
  light, good-enough v0; shiki is a swap not a rewrite). Internal `*.md` links rewritten
  to app routes so the Field Manual's prev/next nav just works. *Nod: rec (Alex, 2026-09-28)*
- **N4 — Styling & navigation.** Hand-rolled CSS with custom-property tokens,
  typography-first (system font stack + a measured reading column), light/dark via
  `prefers-color-scheme`; **no Tailwind or component library in v0** (reversible — a
  warm-up should show us the raw platform first). `react-router` with routes mirroring
  file paths (`/docs/field-manual/08-…`); sidebar tree derived from the glob. *Nod: rec (Alex, 2026-09-28)*

**Build order:** scaffold workspace + app → content glob & routing → markdown pipeline
(GFM → links → highlight → mermaid last) → sidebar/nav → typography & dark mode pass.

**Test plan (G5, scaled to stakes):** behavioural units where logic lives — the
internal-link rewriter (md path → route, anchors preserved) and the sidebar tree builder
get mutation-verified tests; rendering components are exercised by one smoke per page
kind. No test theatre on divs.

**DoD:** dev server renders the Field Manual (mermaid drawn, nav links working), product
overview, PRD, decisions, and the board; sidebar covers the docs tree; light/dark honest;
a doc edit hot-reloads.

## T2 — Board read model + designed pages ✅

**Type** build · **Branch** `e2-t2-board-read-model` · **Started** 2026-09-29 · **Blocked by** —

Parse `board.md` (and epic files) into structure: epic cards, ticket tables with status
chips, the done-history as a timeline; epic pages with metadata/decisions/history rails;
designed landing; top nav + ⌘K search; brand re-skin (D19). Graceful fallback to raw
markdown. The parser is the prime unit-test target (real board fixtures; mutation-verified).
**DoD:** the board page reads as a dashboard, not a rendered README.

## T3 — Build & CI lane ✅

**Type** ops · **Branch** `e2-t2-board-read-model` · **Started** 2026-09-28 · **Blocked by** —

Static `npm run build` snapshotting content at build time; CI job keyed on `web/**`
(tech-notes §6); local preview story. Hosting ruled by Alex 2026-09-28: **GitHub Pages**
(public — same content as the public repo, no new exposure). `pages.yml` (main-push deploy:
test → build with `VITE_BASE` → 404.html SPA fallback → deploy-pages) + `web-ci.yml` (PR
lane). Custom domain **docs.tradebench.amfshr.dev** live (Cloudflare CNAME + `PAGES_BASE=/`,
HTTPS enforced — 2026-09-29). **DoD:** one command produces the static site; CI builds it green.

## T4 — Docs polish & structure (the final E2 sweep) ✅

**Type** docs · **Branch** `e2-t4-docs-sweep` · **Started** 2026-09-30 · **Blocked by** —

The last content/structure pass before E2 closes. Four strands, all landed (Alex, 2026-09-30):

- **(a) Scar callouts (R9)** ✅ — a `> **Scar** — **<title>** · <cost>` blockquote convention
  the renderer styles into the mono-labelled card (no icon, no colour); each of the nine
  chapters now leads its scars with one. (Framed diagrams already shipped in T5.)
- **(b) Book → Field Manual** ✅ — ruled **D21**: docs split by audience. The book became the
  **Field Manual** (Build track — engineering internals); a **User Guide** seat is reserved
  (Use track — end users, DSL). Folder, labels, nav, and all links updated.
- **(c) Decision log — designed page** ✅ — `DecisionsPage`: D-cards (id, status chip, date,
  rendered body) newest-first, parsed from `decisions.md` (`parseDecisionLog`); amber for
  unresolved status.
- **(d) design-sessions nested** ✅ — `docs/design-sessions/` → `docs/design/sessions/`; one
  **Design** sidebar group; all links fixed; link-integrity re-checked.

**DoD met:** scars render as R9 cards; decision log is a designed page; design-sessions nested;
rename ruled (D21). Note: chapter *prose* was left as-is (already tight) beyond the rename and
scar leads — a deeper line-edit can be a future pass if wanted.

## T5 — Ticket-detail pages 🔶

**Type** build · **Branch** `e2-t5-ticket-views` · **Started** 2026-09-30 · **Blocked by** —

Clickable ticket-detail pages: `/board/epics/:slug/:ticket` — structured header (breadcrumb,
status chip, title), a **Done-when checklist** parsed from the ticket's `**DoD:**` clause,
the rich plan section rendered below, and a metadata panel of the *lean* template fields
(type · branch · started · blocked-by · epic · PR-from-note) shown when present, gracefully
absent otherwise. Ticket rows on the board + epic pages become links.

**Design nod (D13) — ruled by Alex 2026-09-30 (all recommendations + the write-model
template idea):** N1 route+data (board row floor + epic-file `## T<n>` section enrichment;
E1/E2 scope) · N2 structured header + DoD checklist (move beyond rendered md) · N3 real
metadata only, never fabricated — the lean field set Alex named (type/branch/started/
blocked-by), rendered when present · N4 clickable rows. Spawned: the **board format spec**
(`docs/reference/board-format.md`, D20) and the **`board-steward` agent** (E0-T5).

**Test plan (G5):** the DoD→checklist splitter, the PR-link parser, and the epic-file
per-ticket section+metadata extractor get mutation-verified tests; components exempt.

**DoD:** a ticket opens as its own page with a structured header, DoD checklist, plan body,
and lean metadata; board/epic ticket rows link to it.
