# E2 🧰 The docs site — ticket plans

**Mission:** the human read model — and the platform's ONE docs site (D18): `web/docs`,
served at `docs.tradebench.amfshr.dev`, housing all static documentation (product, book,
architecture, decisions, the board section; later the DSL reference and REST API/
integration docs). Markdown stays the write model (agents and Alex edit
files; git stays the history); this small React/TS app renders the board *and* the docs
tree — the product overview, the book, design specs — as pleasant pages (D11; scope widened
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

## T1 — Docs & board reader v0 🔶

**Goal:** `npm run dev` serves the repo's markdown as a reading experience: sidebar
navigation over `docs/` + the board, GFM tables, **rendered mermaid**, highlighted code,
working internal links, light/dark, typography that makes the book a pleasure.

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
  to app routes so the book's prev/next nav just works. *Nod: rec (Alex, 2026-09-28)*
- **N4 — Styling & navigation.** Hand-rolled CSS with custom-property tokens,
  typography-first (system font stack + a measured reading column), light/dark via
  `prefers-color-scheme`; **no Tailwind or component library in v0** (reversible — a
  warm-up should show us the raw platform first). `react-router` with routes mirroring
  file paths (`/docs/book/08-…`); sidebar tree derived from the glob. *Nod: rec (Alex, 2026-09-28)*

**Build order:** scaffold workspace + app → content glob & routing → markdown pipeline
(GFM → links → highlight → mermaid last) → sidebar/nav → typography & dark mode pass.

**Test plan (G5, scaled to stakes):** behavioural units where logic lives — the
internal-link rewriter (md path → route, anchors preserved) and the sidebar tree builder
get mutation-verified tests; rendering components are exercised by one smoke per page
kind. No test theatre on divs.

**DoD:** dev server renders the book (mermaid drawn, nav links working), product
overview, PRD, decisions, and the board; sidebar covers the docs tree; light/dark honest;
a doc edit hot-reloads.

## T2 — Board read model ⬜

Parse `board.md` (and epic files) into structure: epic cards, ticket tables with status
chips, the done-history as a timeline. Graceful fallback to raw markdown when parsing
meets something new. The parser is the prime unit-test target (real board fixtures;
mutation-verified). **DoD:** the board page reads as a dashboard, not a rendered README.

## T3 — Build & CI lane 🔶

Static `npm run build` snapshotting content at build time; CI job keyed on `web/**`
(tech-notes §6); local preview story. Hosting ruled by Alex 2026-09-28: **GitHub Pages**
(public — same content as the public repo, no new exposure). Authored on-branch:
`pages.yml` (main-push deploy: test → build with `VITE_BASE=/tradebench/` → 404.html SPA
fallback → deploy-pages; one-off setup: Settings → Pages → Source "GitHub Actions") and
`frontend-ci.yml` (PR lane, not yet a required check). **DoD:** one command produces the static
site; CI builds it green.
