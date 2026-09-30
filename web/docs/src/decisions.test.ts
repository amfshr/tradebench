import { describe, expect, test } from 'vitest';

import { parseDecisions } from './decisions';

const MD = [
  '# Tradebench Decision Log',
  '',
  '## D17 — Database topology: one instance per env; DB per service — **Accepted** (2026-09-28)',
  '',
  'body prose that must be ignored, even if it mentions D99.',
  '',
  '## D18 — Web naming: one docs site; `web/` workspace — **Accepted** (2026-09-30, Alex)',
  '',
  'more body.',
].join('\n');

describe('parseDecisions', () => {
  const map = parseDecisions(MD);

  test('parses id, title (between the dashes), and the accepted date', () => {
    expect(map.get('D18')).toEqual({
      id: 'D18',
      title: 'Web naming: one docs site; `web/` workspace',
      date: '2026-09-30',
    });
  });

  test('title stops at the status, not at a colon or semicolon inside it', () => {
    expect(map.get('D17')?.title).toBe('Database topology: one instance per env; DB per service');
  });

  test('only heading lines become entries — body mentions of D99 are ignored', () => {
    expect(map.has('D99')).toBe(false);
    expect(map.size).toBe(2);
  });
});
