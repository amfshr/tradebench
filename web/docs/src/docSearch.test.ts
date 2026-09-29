import { describe, expect, test } from 'vitest';

import { searchDocs } from './docSearch';

// The corpus is the real sidebar tree; these assertions target ordering/matching
// behaviour that holds regardless of exact content.
describe('searchDocs', () => {
  test('a subsequence match finds the resilience chapter', () => {
    const hits = searchDocs('reslience'.replace('s', ''), 20); // "reilience" — fuzzy
    expect(hits.some((h) => h.route === '/docs/book/08-the-resilience-belt')).toBe(true);
  });

  test('exact-substring beats scattered-subsequence in ranking', () => {
    // "board" appears contiguously in the Board group; ensure a board route ranks first.
    const hits = searchDocs('board', 20);
    expect(hits[0].route.startsWith('/board')).toBe(true);
  });

  test('a query that cannot be a subsequence of anything returns nothing', () => {
    expect(searchDocs('zzqxzzqx', 20)).toEqual([]);
  });

  test('an empty query returns the head of the corpus, capped at the limit', () => {
    expect(searchDocs('', 5)).toHaveLength(5);
  });
});
