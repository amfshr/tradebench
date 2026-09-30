import { describe, expect, test } from 'vitest';

import { resolveLink, routeFor } from './links';

// Expectations are hand-derived from the routing rules, never from running the code.
const REPO = new Set([
  'docs/README.md',
  'docs/field-manual/04-the-pump-and-ack-after-apply.md',
  'docs/field-manual/05-the-capture-store-and-postgres.md',
  'docs/design/architecture.md',
  '.claude/tasks/board.md',
]);
const exists = (p: string) => REPO.has(p);

describe('routeFor', () => {
  test('a doc maps to its path without .md', () => {
    expect(routeFor('docs/product/overview.md')).toBe('/docs/product/overview');
  });

  test('README collapses onto its directory', () => {
    expect(routeFor('docs/README.md')).toBe('/docs');
    expect(routeFor('docs/field-manual/README.md')).toBe('/docs/field-manual');
  });

  test('the board and epics get the /board namespace', () => {
    expect(routeFor('.claude/tasks/board.md')).toBe('/board');
    expect(routeFor('.claude/tasks/epics/e2-docs-site.md')).toBe(
      '/board/epics/e2-docs-site',
    );
  });
});

describe('resolveLink', () => {
  test('a sibling chapter link becomes an internal route', () => {
    expect(
      resolveLink(
        'docs/field-manual/04-the-pump-and-ack-after-apply.md',
        '05-the-capture-store-and-postgres.md',
        exists,
      ),
    ).toEqual({ kind: 'internal', to: '/docs/field-manual/05-the-capture-store-and-postgres' });
  });

  test('.. climbs out of the current directory', () => {
    expect(resolveLink('docs/field-manual/README.md', '../design/architecture.md', exists)).toEqual({
      kind: 'internal',
      to: '/docs/design/architecture',
    });
  });

  test('a docs link into the board lands on /board', () => {
    expect(resolveLink('docs/README.md', '../.claude/tasks/board.md', exists)).toEqual({
      kind: 'internal',
      to: '/board',
    });
  });

  test('anchors survive the rewrite', () => {
    expect(
      resolveLink(
        'docs/field-manual/04-the-pump-and-ack-after-apply.md',
        '05-the-capture-store-and-postgres.md#the-jargon',
        exists,
      ),
    ).toEqual({ kind: 'internal', to: '/docs/field-manual/05-the-capture-store-and-postgres#the-jargon' });
  });

  test('an anchor-only link stays an in-page anchor', () => {
    expect(resolveLink('docs/field-manual/README.md', '#the-scars', exists)).toEqual({
      kind: 'anchor',
      to: '#the-scars',
    });
  });

  test('absolute URLs pass through untouched', () => {
    expect(resolveLink('docs/README.md', 'https://labs.ig.com/streaming-api-guide', exists)).toEqual({
      kind: 'external',
      href: 'https://labs.ig.com/streaming-api-guide',
    });
  });

  test('a markdown link the app does not serve falls back to GitHub', () => {
    expect(resolveLink('docs/README.md', '../ops/queries/README.md', exists)).toEqual({
      kind: 'external',
      href: 'https://github.com/amfshr/tradebench/blob/main/ops/queries/README.md',
    });
  });

  test('a non-markdown file links to GitHub at its resolved path', () => {
    expect(resolveLink('docs/design/architecture.md', '../../compose.yaml', exists)).toEqual({
      kind: 'external',
      href: 'https://github.com/amfshr/tradebench/blob/main/compose.yaml',
    });
  });
});
