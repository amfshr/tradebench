/**
 * Pure parse of `docs/decisions.md` into id → { title, date }, so the epic page's
 * Decisions rail can show a dated, described entry rather than a bare D-number.
 * Heading form: `## D18 — <title> — **Accepted** (2026-09-30, Alex)`.
 */
export interface DecisionEntry {
  id: string;
  title: string;
  date: string;
}

export function parseDecisions(markdown: string): Map<string, DecisionEntry> {
  const out = new Map<string, DecisionEntry>();
  for (const line of markdown.split('\n')) {
    const m = line.match(
      /^##\s+(D\d+)\s+—\s+(.+?)\s+—\s+\*\*[A-Za-z]+\*\*\s*\((\d{4}-\d{2}-\d{2})/,
    );
    if (m) {
      out.set(m[1], { id: m[1], title: m[2].trim(), date: m[3] });
    }
  }
  return out;
}
