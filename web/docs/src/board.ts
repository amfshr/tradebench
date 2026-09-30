/**
 * Pure board parser: `board.md` (the write model) → structured epics, tickets and
 * history (the read model). Faithful to the board's own legend
 * (⬜ queued · 🔶 in progress · ✅ done · 🧊 iced/blocked); anything it can't classify
 * degrades to a passthrough, never a crash — the board page falls back to raw markdown.
 */

export type TicketStatus = 'done' | 'in-progress' | 'queued' | 'iced';

export interface Ticket {
  id: string;
  title: string;
  summary: string;
  status: TicketStatus;
  note: string;
}

export interface Epic {
  id: string;
  num: number;
  emoji: string;
  name: string;
  statusLabel: string;
  mission: string;
  since: string;
  decisions: string[];
  book: string;
  updated: string;
  tickets: Ticket[];
  done: number;
  total: number;
}

export interface HistoryEntry {
  date: string;
  title: string;
}

export interface ParsedBoard {
  title: string;
  epics: Epic[];
  history: HistoryEntry[];
}

const STATUS_EMOJI: [TicketStatus, string][] = [
  ['done', '✅'],
  ['in-progress', '🔶'],
  ['queued', '⬜'],
  ['iced', '🧊'],
];

/** Attention-first (P9: quiet is healthy — active work leads, settled work sinks). */
const STATUS_RANK: Record<TicketStatus, number> = {
  'in-progress': 0,
  queued: 1,
  iced: 2,
  done: 3,
};

export function rankOf(status: TicketStatus): number {
  return STATUS_RANK[status];
}

/**
 * The LEADING status emoji wins — a status cell's note can mention another
 * (e.g. `🔶 … Slice A ✅ on branch`), and the leftmost is the real status.
 */
export function classifyStatus(cell: string): TicketStatus {
  let best: TicketStatus = 'queued';
  let bestAt = Infinity;
  for (const [status, emoji] of STATUS_EMOJI) {
    const at = cell.indexOf(emoji);
    if (at !== -1 && at < bestAt) {
      best = status;
      bestAt = at;
    }
  }
  return best;
}

function statusNote(cell: string): string {
  let note = cell;
  for (const [, emoji] of STATUS_EMOJI) {
    note = note.replaceAll(emoji, '');
  }
  return note.trim();
}

/** Table cells between the outer pipes; an accidental in-cell pipe rejoins into the body. */
function ticketFrom(row: string): Ticket | null {
  const parts = row.split('|').map((c) => c.trim());
  const cells = parts.slice(1, -1);
  if (cells.length < 3) {
    return null;
  }
  const id = cells[0];
  if (!/^T\d+$/.test(id)) {
    return null;
  }
  const statusCell = cells[cells.length - 1];
  const body = cells.slice(1, -1).join(' | ');
  const bold = body.match(/^\*\*(.+?)\*\*\s*(.*)$/s);
  return {
    id,
    title: bold ? bold[1].trim() : '',
    summary: bold ? bold[2].trim() : body,
    status: classifyStatus(statusCell),
    note: statusNote(statusCell),
  };
}

type EpicSeed = Omit<Epic, 'mission' | 'since' | 'decisions' | 'book' | 'updated' | 'tickets' | 'done' | 'total'>;

function epicHeader(line: string): EpicSeed | null {
  const em = line.match(/^##\s+(E\d+)\s+(.*)$/);
  if (!em) {
    return null;
  }
  const rest = em[2];
  const dash = rest.indexOf(' — ');
  const namePart = dash === -1 ? rest : rest.slice(0, dash);
  const statusPart = dash === -1 ? '' : rest.slice(dash + 3);
  const spaceAfterEmoji = namePart.indexOf(' ');
  const emoji = spaceAfterEmoji === -1 ? '' : namePart.slice(0, spaceAfterEmoji);
  const name = spaceAfterEmoji === -1 ? namePart : namePart.slice(spaceAfterEmoji + 1).trim();
  const statusLabel = statusPart.split('(')[0].trim();
  return { id: em[1], num: Number(em[1].slice(1)), emoji, name, statusLabel };
}

/** `**Since** 2026-09-27 · **Decisions** D2, D14 · **Book** ch. 1–9` → the meta fields. */
function parseMeta(line: string): Pick<Epic, 'since' | 'decisions' | 'book'> {
  const since = line.match(/\*\*Since\*\*\s+([\d-]+)/)?.[1] ?? '';
  const book = line.match(/\*\*Book\*\*\s+([^·]+)/)?.[1]?.trim() ?? '';
  const decRaw = line.match(/\*\*Decisions\*\*\s+([^·]+)/)?.[1] ?? '';
  const decisions = decRaw
    .split(',')
    .map((d) => d.trim())
    .filter((d) => /^D\d+$/.test(d));
  return { since, decisions, book };
}

/** The latest YYYY-MM-DD mentioned anywhere in the epic's ticket notes. */
function latestDate(epic: Epic): string {
  const dates = epic.tickets.flatMap((t) => t.note.match(/\d{4}-\d{2}-\d{2}/g) ?? []);
  return dates.sort().at(-1) ?? epic.since;
}

function parseHistory(lines: string[]): HistoryEntry[] {
  const out: HistoryEntry[] = [];
  for (const line of lines) {
    const m = line.match(/^-\s+\*\*(.+?)\*\*/);
    if (!m) {
      continue;
    }
    const dash = m[1].indexOf(' — ');
    out.push(
      dash === -1
        ? { date: '', title: m[1].trim() }
        : { date: m[1].slice(0, dash).trim(), title: m[1].slice(dash + 3).trim() },
    );
  }
  return out;
}

export function parseBoard(markdown: string): ParsedBoard {
  const lines = markdown.split('\n');
  const title = (lines.find((l) => l.startsWith('# ')) ?? '# Board').slice(2).trim();

  const epics: Epic[] = [];
  let current: Epic | null = null;
  let historyLines: string[] = [];
  let inHistory = false;

  for (const line of lines) {
    if (line.startsWith('## ')) {
      inHistory = /^##\s+Done history/i.test(line);
      const header = epicHeader(line);
      if (header) {
        current = {
          ...header,
          mission: '',
          since: '',
          decisions: [],
          book: '',
          updated: '',
          tickets: [],
          done: 0,
          total: 0,
        };
        epics.push(current);
      } else {
        current = null;
      }
      continue;
    }
    if (inHistory) {
      historyLines.push(line);
      continue;
    }
    if (!current) {
      continue;
    }
    if (line.startsWith('**Mission:**')) {
      current.mission = line.slice('**Mission:**'.length).trim();
    } else if (line.startsWith('**Since**')) {
      Object.assign(current, parseMeta(line));
    } else if (line.startsWith('| T')) {
      const ticket = ticketFrom(line);
      if (ticket) {
        current.tickets.push(ticket);
      }
    }
  }

  for (const epic of epics) {
    epic.total = epic.tickets.length;
    epic.done = epic.tickets.filter((t) => t.status === 'done').length;
    epic.updated = latestDate(epic);
  }

  return { title, epics, history: parseHistory(historyLines) };
}

/** History entries tagged with this epic's id (e.g. `E1-T4 Persistence`), newest first. */
export function historyForEpic(board: ParsedBoard, id: string): HistoryEntry[] {
  return board.history.filter((h) => h.title.includes(`${id}-`));
}

/** The first YYYY-MM-DD in a ticket's status note (its done/updated date), or ''. */
export function ticketDate(note: string): string {
  return note.match(/\d{4}-\d{2}-\d{2}/)?.[0] ?? '';
}

/** A ticket-style status for the whole epic, derived from its tickets. */
export function epicStatus(epic: Epic): TicketStatus {
  if (epic.total > 0 && epic.done === epic.total) {
    return 'done';
  }
  if (epic.tickets.some((t) => t.status === 'in-progress')) {
    return 'in-progress';
  }
  return 'queued';
}

export function allTickets(board: ParsedBoard): (Ticket & { epicId: string })[] {
  return board.epics
    .flatMap((e) => e.tickets.map((t) => ({ ...t, epicId: e.id })))
    .sort((a, b) => rankOf(a.status) - rankOf(b.status));
}
