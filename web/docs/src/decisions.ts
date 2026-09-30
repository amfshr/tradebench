/**
 * Pure parse of `docs/decisions.md`. Two shapes from one pass:
 *  - `parseDecisions` → id → entry (the epic page's Decisions rail uses title + date).
 *  - `parseDecisionLog` → ordered full entries with status + body (the Decisions page).
 * Heading form: `## D18 — <title> — **Accepted** (2026-09-30, Alex)`.
 */
export type DecisionStatus = 'Accepted' | 'Provisional' | 'Open' | 'Superseded';

export interface DecisionEntry {
  id: string;
  num: number;
  title: string;
  status: DecisionStatus;
  date: string;
  body: string;
}

// The date may sit behind qualifier text, e.g. `**Accepted** (ANTLR provisional, 2026-09-27)`.
const HEADING = /^##\s+(D(\d+))\s+—\s+(.+?)\s+—\s+\*\*([A-Za-z]+)\*\*\s*\([^)]*?(\d{4}-\d{2}-\d{2})/;

export function parseDecisionLog(markdown: string): DecisionEntry[] {
  const lines = markdown.split('\n');
  const entries: DecisionEntry[] = [];
  let current: DecisionEntry | null = null;
  let body: string[] = [];
  const flush = () => {
    if (current) {
      current.body = body.join('\n').trim();
      entries.push(current);
    }
  };
  for (const line of lines) {
    const m = line.match(HEADING);
    if (m) {
      flush();
      current = {
        id: m[1],
        num: Number(m[2]),
        title: m[3].trim(),
        status: m[4] as DecisionStatus,
        date: m[5],
        body: '',
      };
      body = [];
    } else if (line.startsWith('## ')) {
      // a non-decision section ends the current entry
      flush();
      current = null;
      body = [];
    } else if (current) {
      body.push(line);
    }
  }
  flush();
  return entries;
}

export function parseDecisions(markdown: string): Map<string, DecisionEntry> {
  return new Map(parseDecisionLog(markdown).map((e) => [e.id, e]));
}
