import { describe, expect, test } from 'vitest';

import { prLink, splitDoD, ticketSection } from './ticketDetail';

describe('splitDoD', () => {
  test('splits the DoD clause from the what, and semicolons into items', () => {
    expect(splitDoD('skeleton and CI. **DoD:** green; fast; documented.')).toEqual({
      what: 'skeleton and CI.',
      dod: ['green', 'fast', 'documented'],
    });
  });

  test('strips markdown emphasis and code markers from checklist items', () => {
    expect(splitDoD('x **DoD:** the `drift gate` is **green**; rows link').dod).toEqual([
      'the drift gate is green',
      'rows link',
    ]);
  });

  test('no DoD clause → whole summary is the what, empty checklist', () => {
    expect(splitDoD('just a summary, no dod here')).toEqual({
      what: 'just a summary, no dod here',
      dod: [],
    });
  });
});

describe('prLink', () => {
  test('parses PR #n into the GitHub pull URL', () => {
    expect(prLink('✅ 2026-09-29 (PR #7; merged)')).toBe(
      'https://github.com/amfshr/tradebench/pull/7',
    );
  });

  test('no PR reference → null', () => {
    expect(prLink('🔶 in progress, no pr yet')).toBeNull();
  });
});

describe('ticketSection', () => {
  const EPIC = [
    '# E2 plan',
    '',
    '## T4 — Book polish ⬜',
    '',
    '**Type** docs · **Branch** — · **Started** — · **Blocked by** —',
    '',
    'The scar callout work.',
    '',
    '## T5 — Ticket-detail pages 🔶',
    '',
    '**Type** build · **Branch** `e2-t5-ticket-views` · **Started** 2026-09-30 · **Blocked by** T4',
    '',
    'The rich plan body,',
    'across two lines.',
    '',
    '## T6 — Something else',
    '',
    'not part of T5.',
  ].join('\n');

  test('extracts the metadata line and the body, stopping at the next section', () => {
    const s = ticketSection(EPIC, 'T5');
    expect(s?.meta).toEqual({
      type: 'build',
      branch: 'e2-t5-ticket-views',
      started: '2026-09-30',
      blockedBy: 'T4',
    });
    expect(s?.body).toBe('The rich plan body,\nacross two lines.');
  });

  test('em-dash metadata fields are treated as absent, not literal', () => {
    expect(ticketSection(EPIC, 'T4')?.meta).toEqual({ type: 'docs' });
  });

  test('T5 must not swallow the following T6 section', () => {
    expect(ticketSection(EPIC, 'T5')?.body.includes('not part of T5')).toBe(false);
  });

  test('a ticket id matching a prefix (T5) does not match T55-style ids', () => {
    const md = '## T55 — Decoy\n\nbody';
    expect(ticketSection(md, 'T5')).toBeNull();
  });

  test('no section for the id → null', () => {
    expect(ticketSection(EPIC, 'T9')).toBeNull();
  });
});
