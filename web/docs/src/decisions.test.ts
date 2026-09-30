import { describe, expect, test } from 'vitest';

import { parseDecisionLog, parseDecisions } from './decisions';

const MD = [
  '# Tradebench Decision Log',
  '',
  '> intro blockquote, ignored.',
  '',
  '## D17 — Database topology: one instance per env; DB per service — **Accepted** (2026-09-28)',
  '',
  'Body of seventeen. Mentions D99 in prose but that must not become an entry.',
  '',
  '## D18 — Web naming: one docs site; `web/` workspace — **Provisional** (2026-09-30, Alex)',
  '',
  'First paragraph of eighteen.',
  '',
  'Second paragraph of eighteen.',
  '',
  '## Some non-decision section',
  '',
  'this ends D18 and is not itself an entry.',
].join('\n');

describe('parseDecisionLog', () => {
  const log = parseDecisionLog(MD);

  test('parses each decision in document order with id, num, title, status, date', () => {
    expect(log.map((e) => e.id)).toEqual(['D17', 'D18']);
    expect(log[1]).toMatchObject({
      id: 'D18',
      num: 18,
      title: 'Web naming: one docs site; `web/` workspace',
      status: 'Provisional',
      date: '2026-09-30',
    });
  });

  test('body captures all prose up to the next heading, and stops at a non-decision heading', () => {
    expect(log[1].body).toBe('First paragraph of eighteen.\n\nSecond paragraph of eighteen.');
    expect(log[1].body.includes('this ends D18')).toBe(false);
  });

  test('a D-mention inside body prose is not parsed as an entry', () => {
    expect(log.map((e) => e.id)).not.toContain('D99');
  });
});

describe('parseDecisions', () => {
  test('indexes entries by id for the rail (title + date available)', () => {
    const map = parseDecisions(MD);
    expect(map.get('D17')?.title).toBe('Database topology: one instance per env; DB per service');
    expect(map.get('D17')?.date).toBe('2026-09-28');
    expect(map.size).toBe(2);
  });
});
