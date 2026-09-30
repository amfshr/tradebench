/**
 * Pure ticket-detail logic: split the board row's DoD into a checklist, parse a PR link
 * from the note, and extract a ticket's rich section + lean metadata (board-format.md,
 * D20) from its epic plan file. Everything degrades gracefully — missing pieces yield
 * empty results, never throws.
 */
import { GITHUB_BLOB } from './links';

const GITHUB_PR = GITHUB_BLOB.replace(/\/blob\/main\/$/, '/pull/');

export interface TicketMeta {
  type?: string;
  branch?: string;
  started?: string;
  blockedBy?: string;
}

export interface TicketSection {
  meta: TicketMeta;
  body: string;
}

/** "skeleton and CI. **DoD:** green; fast." → { what, dod: ['green', 'fast'] }. */
export function splitDoD(summary: string): { what: string; dod: string[] } {
  const at = summary.indexOf('**DoD:**');
  if (at === -1) {
    return { what: summary.trim(), dod: [] };
  }
  const what = summary.slice(0, at).trim();
  const dod = summary
    .slice(at + '**DoD:**'.length)
    .split(';')
    .map((s) => s.trim().replace(/\*\*/g, '').replace(/`/g, '').replace(/\.$/, '').trim())
    .filter((s) => s.length > 0);
  return { what, dod };
}

/** `PR #7` in a status note → the GitHub PR URL; null if none. */
export function prLink(note: string): string | null {
  const m = note.match(/PR #(\d+)/);
  return m ? `${GITHUB_PR}${m[1]}` : null;
}

function parseTicketMeta(line: string): TicketMeta {
  const meta: TicketMeta = {};
  const type = line.match(/\*\*Type\*\*\s+([A-Za-z]+)/)?.[1];
  const branch = line.match(/\*\*Branch\*\*\s+`([^`]+)`/)?.[1];
  const started = line.match(/\*\*Started\*\*\s+([\d-]+)/)?.[1];
  const blockedBy = line.match(/\*\*Blocked by\*\*\s+([^·\n]+)/)?.[1]?.trim();
  if (type) meta.type = type;
  if (branch) meta.branch = branch;
  if (started && started !== '—') meta.started = started;
  if (blockedBy && blockedBy !== '—') meta.blockedBy = blockedBy;
  return meta;
}

/**
 * Extract a ticket's section from its epic plan file: the metadata line (pulled out) and
 * the rest as the rendered body. Null when the epic file has no `## T<n>` section.
 */
export function ticketSection(epicMarkdown: string, ticketId: string): TicketSection | null {
  const lines = epicMarkdown.split('\n');
  const startPattern = new RegExp(`^##\\s+${ticketId}\\b`);
  let start = -1;
  for (let i = 0; i < lines.length; i++) {
    if (startPattern.test(lines[i])) {
      start = i;
      break;
    }
  }
  if (start === -1) {
    return null;
  }
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (lines[i].startsWith('## ')) {
      end = i;
      break;
    }
  }
  const section = lines.slice(start + 1, end);
  let meta: TicketMeta = {};
  const body: string[] = [];
  for (const line of section) {
    if (line.startsWith('**Type**') || /\*\*Branch\*\*/.test(line)) {
      meta = parseTicketMeta(line);
    } else {
      body.push(line);
    }
  }
  return { meta, body: body.join('\n').trim() };
}
