# web — the web apps workspace

npm-workspaces root for Tradebench's web apps, each named for the domain it serves (D18):

- **`docs/`** — the one docs site (`docs.tradebench.amfshr.dev`): all static documentation
  — product overview, the book, design specs, decisions, the board — rendered from the
  repo's markdown. `npm run dev -w docs` from this directory.
- **`platform/`** — seat reserved: the product SPA (`tradebench.amfshr.dev`).

Conventions: `docs/design/tech-notes.md` §6.
