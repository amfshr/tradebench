# Design session — brand & visual identity (docs site first surface)

**Status: CLOSED** (2026-09-29, same day). Phase 1: Alex ran the full exploration in
Claude's design tool — a ten-turn session whose export is itself a decision log
(D-01…D-09 with picks, rejections, and rationale), archived in
`docs/design/research/2026-09-29-brand-explorations/`. Phase 2: Claude audited it against
doctrine (WCAG AA computed for every token pair; red/green reservation verified;
quiet-is-healthy honored) and put it to Alex as R1–R11. **Alex ruled: all as recommended
("all rec, go ahead and execute").** **Resolved:** the visual identity for `web/docs` and
later `web/platform` — spec at `docs/design/brand.md`; decision D19. **Spawned:** token
re-skin + wordmark + two-candles favicon in `web/docs` (rides the T2 PR); R8 elevated to
standing platform doctrine; T2 design intent = the exploration's board/epic IA (R11b).

## Grounding (what is already ruled — not re-litigated here)

- **Name: Tradebench** ✅ (PRD §1, 2026-09-25) — "the bench you work at"; collision check
  done. Provider-agnostic identity ✅ — the product is named after no broker. Home:
  `tradebench.amfshr.dev`; docs at `docs.tradebench.amfshr.dev` (D18).
- **Thesis** (PRD §1): compress the loop between idea and verdict. Brand serves **craft
  and clarity, not conversion** — three trusted users, no strangers, no marketing site
  (PRD §2, §12).
- Current implementation state: token-block CSS in `web/docs` (GitHub-ish neutrals, green
  accent `#0b6e4f`/`#4ed4a4`, Inter + JetBrains Mono) — explicitly a placeholder awaiting
  this session.
- **Seed-pack sweep verdict (librarian, 2026-09-29):** binding and not re-litigated —
  the name + rationale (D5; S1-Q8/Q9: never provider-linked), `tradebench.amfshr.dev` +
  subdomain-per-sub-platform (PRD §5, S2-J1), and the **open-source charting library
  lean (TradingView Lightweight Charts, S5-Q5/PRD §11)** — meaning the brand must
  survive a third-party chart renderer's theming, folded into P6. On colors, theme,
  typography, logo, and look-and-feel proper: **the pack is silent — unclaimed
  territory.** The grill sessions' "very very nice" moments are feature enthusiasm
  (live charts, trade→replay), not visual-style reactions.

## The lesson (industry paradigms, condensed)

1. **Fintech-trust** (banks: navy, conservative) — signals safety; fails into bland
   sameness. Wrong register: we are a workshop, not a custodian.
2. **Trading-terminal** (Bloomberg/TradingView: dark-first, dense, red/green everywhere)
   — right for live monitoring; fails long-form reading, and **red/green as brand colors
   collide with market semantics**. Lesson kept: reserve red/green for candles and P&L,
   forever. (This convicts the current green accent.)
3. **Developer-tool modern** (Stripe/Linear-era: restrained neutrals + one accent,
   typography-led, docs-as-product, dark/light parity) — closest fit; failure mode is
   trend-chasing (gradients, glassmorphism) that ages badly.
4. **Docs-system default** (Material/Docusaurus) — cheap coherence, zero identity.

## Strawman (non-committed)

Direction B "The Workbench": warm dark neutrals + a **brass/amber** accent (workshop
tools, not market colors), Inter + JetBrains Mono kept, mono-heavy lowercase wordmark,
light mode as "paper on the bench". Rationale: ownable (the name IS the metaphor),
avoids fintech-blue cliché and the red/green collision. Directions A ("Quiet Terminal")
and C ("Paper & Ink") are explored honestly, not as strawman foils.

## Exploration briefs (phase 1 — run in Claude's visual design tool)

**Shared context block** (paste before/with every prompt) and briefs P1–P6 are recorded
verbatim in this session's chat hand-off; canonical copies below.

### Shared context

> Tradebench is a private multi-user web platform for the full algorithmic-trading
> pipeline — data collection, charting, a custom strategy language, backtesting, and
> eventually live trading bots — built by one engineer for himself, his brother, and
> their dad. Public repo, private product: the brand serves craft and clarity, not
> marketing. The name is the metaphor: the bench you work at — a workshop where trading
> ideas are built and tested against years of data until they earn trust. Product thesis:
> "compress the loop between idea and verdict" — fast honest answers, no hype. First
> surface: a documentation site (docs.tradebench) — product docs, a teaching book written
> in a scars-and-lessons voice, system architecture diagrams, a development board. Later:
> the platform app (charts, backtests, bot dashboards) must feel like the same product.
> Hard constraints: (1) red and green are RESERVED for market semantics (candles up/down,
> P&L) — the brand accent must be neither red nor green; (2) light AND dark modes are
> both first-class; (3) everything must reduce to flat CSS design tokens — give real hex
> values, no heavy gradients or glassmorphism; (4) current type is Inter (text) +
> JetBrains Mono (code/data) — keep unless you can argue better; (5) typography first:
> the site is mostly long-form reading; (6) WCAG AA contrast throughout.

### P1 — three direction boards
One page, three contrasting brand direction boards side by side: **A "Quiet Terminal"**
(dark-first, market-adjacent restraint), **B "The Workbench"** (warm neutrals,
brass/amber accent, workshop craft), **C "Paper & Ink"** (light-first, editorial, a serif
for long-form). Each board: 6-swatch palette **with hex** (background, raised surface,
text, muted text, accent, border) for BOTH light and dark; a type specimen (h1, body,
code); the word "tradebench" set as a small wordmark in that direction's voice; one
sentence on the mood. No mockups yet — boards only.

### P2 — wordmark & mark
Lowercase "tradebench" wordmark studies (mono-influenced vs humanist), plus abstract
mark candidates drawn from the product's own truths: a sealed candle (the bar that
closed), a gap in a grid being healed, a bench profile, a tick on an event clock. Show
each mark at favicon size (16px) — legibility there is a hard filter. Both modes.

### P3 — docs landing page
Landing page for docs.tradebench in the strongest direction (state which): compact hero
(wordmark, one-liner "the bench you work at", quiet), entry cards for Product overview /
The Field Manual / Architecture / Decisions / Board, a visible sidebar + reading-column shell so
the page structure reads as a docs site, not a marketing splash. Show light AND dark.

### P4 — the board as a dashboard
A "development board" page: epics as cards with mission one-liners; ticket rows with
status chips (queued / in-progress / done / iced); a done-history vertical timeline.
Aesthetic rule from the product's own doctrine: **quiet is healthy** — visual noise only
where something needs attention. Both modes.

### P5 — book chapter reading page
Long-form reading specimen for a teaching book chapter: chapter title, h2/h3 rhythm,
~66-char measure, a code block, a framed architecture-diagram placeholder, a styled
"scar" callout (the Field Manual tells failure stories — give them a dignified treatment, not a
warning-triangle cliché), and a right-hand "on this page" rail. Typography is the whole
product here. Both modes.

### P6 (stretch) — platform coherence teaser
One dashboard frame of the FUTURE platform app in the same direction: a candlestick
chart panel (this is where red/green live, and only here), a running-bots status strip,
an equity curve card — proving the brand survives contact with real market UI.

## Rulings (R1–R11 — Alex, 2026-09-29, all as recommended)

R1 Direction: Quiet Terminal (ratifies exploration D-01; strawman Workbench conceded on
the brass/amber-attention collision) · R2 Tokens (D-02 tables — see brand.md) · R3 Type:
Inter + JetBrains Mono, Newsreader rejected (D-03) · R4 Wordmark `tradebench▊` mono-500
cursor block (D-04) · R5 Mark/favicon: two candles, accent hollow (D-05) · R6 Sidebar
active: tree rail, 2px accent segment (D-06) · R7 Attention = amber, at most once per
screen (D-07) · R8 Market colours only for up/down + P&L; strategy uses accent — market
and strategy never share a colour — **standing platform doctrine** (D-08) · R9 Scar
callout: mono spine, dated cost, Lesson line, no icon/colour (D-09) · R10 Guardrail:
light-mode up/down as text is AA-large only → P&L text semibold-large or darkened
variants · R11 Scope: mock content is fiction, none ratified; the exploration's
epic/ticket IA becomes T2 design intent.

Contrast audit (computed, both modes): every text-role pair ≥ 4.5:1 AA; the sole
exception is R10's case. Full ratios recorded in session chat and re-derivable from the
token table.

## Satellites

`docs/design/brand.md` (the spec) · D19 in `docs/decisions.md` · board Done-history +
menu refresh · re-skin commit on `e2-t2-board-read-model`.
