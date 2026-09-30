import { describe, expect, test } from 'vitest';

import {
  allTickets,
  classifyStatus,
  epicStatus,
  historyForEpic,
  parseBoard,
  rankOf,
  ticketDate,
} from './board';

// A fixture shaped exactly like the real board.md (header forms, table forms, history
// bullets) but with invented content. Expectations are hand-derived from the format.
const FIXTURE = `# Tradebench Board

> Legend line, ignored.

**Guide (x):** prose that is not an epic.

---

## E1 📡 Collection service, deployed 24/7 — ACTIVE

**Since** 2026-09-25 · **Decisions** D2, and TBD, D17 · **Book** ch. 1–9

**Mission:** stream and store market data.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Foundations.** skeleton and CI. **DoD:** green. | ✅ 2026-09-27 (PR #1) |
| T2 | **IG client.** REST + stream. | ✅ 2026-09-28 |
| T3 | **Streaming.** ticks + bars. | 🔶 (nod ruled) |
| T4 | **Persistence.** Flyway. | ⬜ |

## E2 🧰 The docs site (\`web/docs\` → docs.tradebench) — ACTIVE (planned 2026-09-28)

**Mission:** the human read model.

| # | Ticket | Status |
|---|--------|--------|
| T1 | **Reader v0.** markdown as pages. | ✅ 2026-09-28 (PR #6) |
| T2 | **Board read model.** parse into a dashboard. | 🔶 |

## Horizon epics — named, unplanned

- **E4** something that is not an epic header here.

## Done history

- **2026-09-27 — Sanity lap** (menu ①): cold-start playback proved the handoff.
  a **bold phrase** on a continuation line must not become an entry.
- **2026-09-28 — E1-T4 Persistence** ✅: Flyway V1 landed.
- **2026-09-30 — E12-T1 decoy epic** ✅: must not leak onto E1's page.
`;

describe('classifyStatus', () => {
  test('maps each legend emoji, defaulting unknown cells to queued', () => {
    expect(classifyStatus('✅ 2026-09-27')).toBe('done');
    expect(classifyStatus('🔶 (nod ruled)')).toBe('in-progress');
    expect(classifyStatus('⬜')).toBe('queued');
    expect(classifyStatus('🧊 blocked')).toBe('iced');
    expect(classifyStatus('no emoji at all')).toBe('queued');
  });

  test('the leading emoji wins when the note mentions another (real T5 case)', () => {
    expect(classifyStatus('🔶 (nod ruled; Slice A ✅ on branch)')).toBe('in-progress');
  });
});

describe('parseBoard', () => {
  const board = parseBoard(FIXTURE);

  test('reads the board title', () => {
    expect(board.title).toBe('Tradebench Board');
  });

  test('finds exactly the epic headers, not lookalike sections or bullets', () => {
    expect(board.epics.map((e) => e.id)).toEqual(['E1', 'E2']);
  });

  test('splits emoji, name and status even when the name contains parentheses', () => {
    const e2 = board.epics[1];
    expect(e2.emoji).toBe('🧰');
    expect(e2.name).toBe('The docs site (`web/docs` → docs.tradebench)');
    expect(e2.statusLabel).toBe('ACTIVE');
    expect(e2.num).toBe(2);
  });

  test('parses ticket bold-lead into title and the rest into summary', () => {
    const t1 = board.epics[0].tickets[0];
    expect(t1.id).toBe('T1');
    expect(t1.title).toBe('Foundations.');
    expect(t1.summary).toBe('skeleton and CI. **DoD:** green.');
    expect(t1.status).toBe('done');
    expect(t1.note).toBe('2026-09-27 (PR #1)');
  });

  test('skips the header and separator rows of the ticket table', () => {
    expect(board.epics[0].tickets.map((t) => t.id)).toEqual(['T1', 'T2', 'T3', 'T4']);
  });

  test('computes done/total per epic', () => {
    expect(board.epics[0].done).toBe(2);
    expect(board.epics[0].total).toBe(4);
    expect(board.epics[1].done).toBe(1);
    expect(board.epics[1].total).toBe(2);
  });

  test('captures the mission line', () => {
    expect(board.epics[0].mission).toBe('stream and store market data.');
  });

  test('reads the metadata line: since, decisions (D-numbers only), book', () => {
    const e1 = board.epics[0];
    expect(e1.since).toBe('2026-09-25');
    expect(e1.decisions).toEqual(['D2', 'D17']);
    expect(e1.book).toBe('ch. 1–9');
  });

  test('derives "updated" from the latest date in the ticket notes, not "since"', () => {
    expect(board.epics[0].updated).toBe('2026-09-28');
  });

  test('reads history top-level bullets only, splitting date from title', () => {
    expect(board.history).toEqual([
      { date: '2026-09-27', title: 'Sanity lap' },
      { date: '2026-09-28', title: 'E1-T4 Persistence' },
      { date: '2026-09-30', title: 'E12-T1 decoy epic' },
    ]);
  });

  test('degrades to zero epics on prose with no headers (raw-markdown fallback contract)', () => {
    expect(parseBoard('# Notes\n\nJust prose, no epic headers here.').epics).toEqual([]);
  });

  test('reconstructs a ticket summary that contains an in-cell pipe', () => {
    const md = [
      '# B',
      '',
      '## E1 📡 X — ACTIVE',
      '',
      '| # | Ticket | Status |',
      '|---|--------|--------|',
      '| T1 | **Ranges.** ticks | bars. | ⬜ |',
      '',
    ].join('\n');
    const t = parseBoard(md).epics[0].tickets[0];
    expect(t.title).toBe('Ranges.');
    expect(t.summary).toBe('ticks | bars.');
  });
});

describe('allTickets', () => {
  test('flattens across epics and sorts attention-first (active before settled)', () => {
    const ordered = allTickets(parseBoard(FIXTURE)).map((t) => t.status);
    for (let i = 1; i < ordered.length; i++) {
      expect(rankOf(ordered[i - 1])).toBeLessThanOrEqual(rankOf(ordered[i]));
    }
    expect(ordered[0]).toBe('in-progress');
    expect(ordered[ordered.length - 1]).toBe('done');
  });

  test('tags each ticket with its epic id', () => {
    const first = allTickets(parseBoard(FIXTURE)).find((t) => t.id === 'T3');
    expect(first?.epicId).toBe('E1');
  });
});

describe('ticketDate', () => {
  test('extracts the first date from a status note, or empty', () => {
    expect(ticketDate('✅ 2026-09-27 (PR #1)')).toBe('2026-09-27');
    expect(ticketDate('(nod ruled)')).toBe('');
  });
});

describe('epicStatus', () => {
  const board = parseBoard(FIXTURE);
  test('in-progress when a ticket is in progress and not all done', () => {
    expect(epicStatus(board.epics[0])).toBe('in-progress');
  });
  test('done only when every ticket is done', () => {
    const allDone = { ...board.epics[0], done: 4, total: 4, tickets: board.epics[0].tickets.map((t) => ({ ...t, status: 'done' as const })) };
    expect(epicStatus(allDone)).toBe('done');
  });
  test('queued when tickets exist, none in progress, and not all done', () => {
    const mixed = {
      ...board.epics[0],
      done: 1,
      total: 3,
      tickets: [
        { ...board.epics[0].tickets[0], status: 'done' as const },
        { ...board.epics[0].tickets[0], id: 'T2', status: 'queued' as const },
        { ...board.epics[0].tickets[0], id: 'T3', status: 'iced' as const },
      ],
    };
    expect(epicStatus(mixed)).toBe('queued');
  });
  test('an empty epic (no tickets) is queued, never done', () => {
    const empty = { ...board.epics[0], done: 0, total: 0, tickets: [] };
    expect(epicStatus(empty)).toBe('queued');
  });
});

describe('historyForEpic', () => {
  test('keeps only entries tagged with the epic id — the dash boundary excludes E12 from E1', () => {
    const board = parseBoard(FIXTURE);
    expect(historyForEpic(board, 'E1').map((h) => h.title)).toEqual(['E1-T4 Persistence']);
    expect(historyForEpic(board, 'E9')).toEqual([]);
  });
});
