# E0 🤖 AI operating framework — ticket plans

**Mission:** make AI a reliable standing development partner (PRD §9's AI-workable NFR):
explicit roles, hard guardrails, session protocols, and a standardized `.claude/` + `docs/`
structure — so any session, however cold, is safe and productive by default.

**References:** `.claude/ai-framework.md` (the contract) · engineering playbook §2 (the
testing doctrine G5 binds to) · PRD §9 · board E0 section. T1–T3 and T5 are done and render
from their board rows; this file was born 2026-11-22 for T4's plan.

---

## T4 — Guardrail enforcement 🔶 (started 2026-11-22; the design nod held 2026-11-22, Alex — D13)

**Type** build · **Branch** `e0-t4-guardrail-enforcement` (cut from main `443c642`, 2026-11-22) · **Started** 2026-11-22 · **Blocked by** —

**DoD (the board row):** planted violations caught in CI; permissions documented in the
framework doc.

**Design nod (D13) — held 2026-11-22, Alex: "i agree" to all six rulings.**

1. **CI scanner.** gitleaks (pinned) as a step in `ci.yml` over the PR's changes, with custom
   rules for this repo's credential shapes: IG CST / `X-SECURITY-TOKEN` and the API key, R2
   access keys, the healthchecks ping URL, Grafana `glc_` tokens, private-key blocks.
   GitGuardian kept as a second opinion.
2. **The `.env` value check as a local pre-commit hook.** `.githooks/pre-commit`, enabled once
   with `git config core.hooksPath .githooks`: it reads the local env files and fails a commit
   whose staged changes contain any value, naming the variable only; it also runs gitleaks on
   the staged changes. Bypassable — so CI stays the backstop and the doctrine reviewer's `.env`
   leak check stays.
3. **Strategy content guarded by location and marker** until E3 defines a strategy file: CI
   fails on any file under `strategies/private/` and on any file carrying the marker line
   `tradebench: private`. E3 revisits.
4. **Proven red.** CI tests run the scanners against planted fixtures — a fake IG token, a fake
   R2 key, a marked strategy file, a `strategies/private/` file — and pass only if each is
   caught; the same for the hook's value check. Mutation = remove a rule → red. The fixtures
   are allow-listed for the real scan.
5. **A committed `.claude/settings.json` permissions baseline.** Deny Read of `.env` / `.env.*`
   and of the env files under `/etc/tradebench` and `~/.config/tradebench`; deny
   `git push --force` and `git add -A` / `git add .`; allow routine read-only commands
   (`git status` / `diff` / `log`, the Gradle tests, the docs tests). Documented in
   `.claude/ai-framework.md`. No Claude Code hooks (no real need shown).
6. **A one-off local history sweep** — gitleaks + the value check over all of git history,
   reported by variable name only. Any finding is decided with Alex (rewriting public history
   is irreversible, G7).

**The history sweep (2026-11-22) — clean, nothing to decide.** gitleaks over every commit on every branch, with its
defaults and with this repo's rules: no findings. The value check against `.env` and `~/.config/tradebench/*.env`:
three variables matched — the IG demo account id, the IG demo identifier and the local instance name — every match in
a commit dated before 2026-10-10 21:10, when `120415d` and `c9442f9` redacted them; `120415d` records Alex's ruling
that day, "history keeps the old values; Alex is fine with that". Nothing has leaked since. A fourth match — the R2
remote's provider, a vendor name — was the checker's own false positive: the value is published in
`deploy/env/*.env.example`, so every committed `*.env.example` now counts as public, not only the root one.

**Then the E0 close-out.** The contract review `.claude/ai-framework.md` §6 asks for at an epic
boundary (E1 shipped), and E0 marked complete — on Alex's confirmation, after T4 merges.
