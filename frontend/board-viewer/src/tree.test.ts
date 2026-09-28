import { describe, expect, test } from 'vitest';

import { buildTree, neighbours, normalizeRoute } from './tree';

describe('normalizeRoute', () => {
  test('the root and trailing slashes normalise to canonical routes', () => {
    expect(normalizeRoute('/')).toBe('/docs');
    expect(normalizeRoute('')).toBe('/docs');
    expect(normalizeRoute('/docs/book/')).toBe('/docs/book');
    expect(normalizeRoute('/board')).toBe('/board');
  });
});

describe('buildTree', () => {
  const paths = [
    '.claude/tasks/epics/e1-collection-service.md',
    '.claude/tasks/board.md',
    'docs/README.md',
    'docs/book/10-imaginary-late-chapter.md',
    'docs/book/02-threads-and-the-callback-boundary.md',
    'docs/book/README.md',
    'docs/decisions.md',
    'docs/inherited/grill/session-1-vision.md',
    'docs/product/overview.md',
  ];
  const tree = buildTree(paths);

  test('groups appear in fixed order and empty groups vanish', () => {
    expect(tree.map((g) => g.label)).toEqual([
      'Product',
      'The book',
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
    const book = tree.find((g) => g.label === 'The book');
    expect(book?.items).toEqual([
      {
        route: '/docs/book/02-threads-and-the-callback-boundary',
        label: '2 · Threads and the callback boundary',
      },
      { route: '/docs/book/10-imaginary-late-chapter', label: '10 · Imaginary late chapter' },
      { route: '/docs/book', label: 'Index' },
    ]);
  });

  test('the board leads its group; epics nest under it with their directory label', () => {
    const board = tree.find((g) => g.label === 'Board');
    expect(board?.items).toEqual([
      { route: '/board', label: 'Board' },
      { route: '/board/epics/e1-collection-service', label: 'epics/E1 collection service' },
    ]);
  });

  test('neighbours page-turn stays inside the current group', () => {
    expect(neighbours(tree, '/docs/book/10-imaginary-late-chapter')).toEqual({
      prev: {
        route: '/docs/book/02-threads-and-the-callback-boundary',
        label: '2 · Threads and the callback boundary',
      },
      next: { route: '/docs/book', label: 'Index' },
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
