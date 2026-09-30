import { describe, expect, test } from 'vitest';

import { buildTree, neighbours, normalizeRoute } from './tree';

describe('normalizeRoute', () => {
  test('the root and trailing slashes normalise to canonical routes', () => {
    expect(normalizeRoute('/')).toBe('/docs');
    expect(normalizeRoute('')).toBe('/docs');
    expect(normalizeRoute('/docs/field-manual/')).toBe('/docs/field-manual');
    expect(normalizeRoute('/board')).toBe('/board');
  });
});

describe('buildTree', () => {
  const paths = [
    '.claude/tasks/epics/e1-collection-service.md',
    // Sorts BEFORE board.md: proves the board leads by rule, not by alphabet (review F1).
    '.claude/tasks/00-inbox.md',
    '.claude/tasks/board.md',
    'docs/README.md',
    'docs/field-manual/10-imaginary-late-chapter.md',
    'docs/field-manual/02-threads-and-the-callback-boundary.md',
    'docs/field-manual/README.md',
    'docs/decisions.md',
    'docs/inherited/grill/session-1-vision.md',
    'docs/product/overview.md',
  ];
  const tree = buildTree(paths);

  test('groups appear in fixed order and empty groups vanish', () => {
    expect(tree.map((g) => g.label)).toEqual([
      'Product',
      'Field Manual',
      'Decisions',
      'Inherited (read-only)',
      'Board',
    ]);
  });

  test('the docs map itself belongs to no group (it is the home page)', () => {
    const allRoutes = tree.flatMap((g) => g.items.map((i) => i.route));
    expect(allRoutes).not.toContain('/docs');
  });

  test('book chapters sort in chapter order with pretty labels', () => {
    const book = tree.find((g) => g.label === 'Field Manual');
    expect(book?.items).toEqual([
      {
        route: '/docs/field-manual/02-threads-and-the-callback-boundary',
        label: '2 · Threads and the callback boundary',
      },
      { route: '/docs/field-manual/10-imaginary-late-chapter', label: '10 · Imaginary late chapter' },
      { route: '/docs/field-manual', label: 'Index' },
    ]);
  });

  test('the board leads its group even when another file sorts before it', () => {
    const board = tree.find((g) => g.label === 'Board');
    expect(board?.items).toEqual([
      { route: '/board', label: 'Board' },
      { route: '/.claude/tasks/00-inbox', label: '0 · Inbox' },
      { route: '/board/epics/e1-collection-service', label: 'epics/E1 collection service' },
    ]);
  });

  test('neighbours page-turn stays inside the current group', () => {
    expect(neighbours(tree, '/docs/field-manual/10-imaginary-late-chapter')).toEqual({
      prev: {
        route: '/docs/field-manual/02-threads-and-the-callback-boundary',
        label: '2 · Threads and the callback boundary',
      },
      next: { route: '/docs/field-manual', label: 'Index' },
    });
    // Product has one item: no wrap into the next group, no phantom neighbours.
    expect(neighbours(tree, '/docs/product/overview')).toEqual({
      prev: undefined,
      next: undefined,
    });
    expect(neighbours(tree, '/no/such/route')).toEqual({});
  });

  test('nested inherited files keep their directory in the label', () => {
    const inherited = tree.find((g) => g.label === 'Inherited (read-only)');
    expect(inherited?.items).toEqual([
      { route: '/docs/inherited/grill/session-1-vision', label: 'grill/Session 1 vision' },
    ]);
  });
});
