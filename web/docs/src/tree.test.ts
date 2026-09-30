import { describe, expect, test } from 'vitest';

import { buildTree, flattenLeaves, neighbours, normalizeRoute, type TreeNode } from './tree';

describe('normalizeRoute', () => {
  test('the root and trailing slashes normalise to canonical routes', () => {
    expect(normalizeRoute('/')).toBe('/docs');
    expect(normalizeRoute('')).toBe('/docs');
    expect(normalizeRoute('/docs/field-manual/')).toBe('/docs/field-manual');
    expect(normalizeRoute('/board')).toBe('/board');
  });
});

const PATHS = [
  '.claude/tasks/epics/e1-collection-service.md',
  '.claude/tasks/00-inbox.md',
  '.claude/tasks/board.md',
  'docs/README.md',
  'docs/field-manual/10-imaginary-late-chapter.md',
  'docs/field-manual/02-threads-and-the-callback-boundary.md',
  'docs/field-manual/README.md',
  'docs/design/architecture.md',
  'docs/design/sessions/2026-09-29-brand.md',
  'docs/design/sessions/2026-09-27-indicators.md',
  'docs/decisions.md',
];
const tree = buildTree(PATHS);
const group = (label: string) => tree.find((g) => g.label === label);
const labelsOf = (nodes: TreeNode[] | undefined) => (nodes ?? []).map((n) => n.label);

describe('buildTree', () => {
  test('groups appear in fixed order; empty groups vanish; the docs map is not a leaf', () => {
    expect(tree.map((g) => g.label)).toEqual(['Field Manual', 'Design', 'Decisions', 'Board']);
    const allRoutes = tree.flatMap((g) => flattenLeaves(g.nodes).map((l) => l.route));
    expect(allRoutes).not.toContain('/docs');
  });

  test('a subdirectory becomes a nested folder node, not a slash-prefixed leaf', () => {
    const design = group('Design')?.nodes ?? [];
    // files before folders, so architecture precedes the Sessions folder
    expect(labelsOf(design)).toEqual(['Architecture', 'Sessions']);
    const sessions = design.find((n) => n.label === 'Sessions');
    expect(sessions?.route).toBeUndefined();
    expect(labelsOf(sessions?.children)).toEqual([
      '2026-09-27 · Indicators',
      '2026-09-29 · Brand',
    ]);
    expect(sessions?.children?.[0].route).toBe('/docs/design/sessions/2026-09-27-indicators');
  });

  test('chapters sort in numeric order with pretty labels; README becomes Index', () => {
    expect(labelsOf(group('Field Manual')?.nodes)).toEqual([
      '2 · Threads and the callback boundary',
      '10 · Imaginary late chapter',
      'Index',
    ]);
  });

  test('the board leaf leads its group even when a file sorts before it; epics nest', () => {
    const board = group('Board')?.nodes ?? [];
    expect(labelsOf(board)).toEqual(['Board', '0 · Inbox', 'Epics']);
    const epics = board.find((n) => n.label === 'Epics');
    expect(labelsOf(epics?.children)).toEqual(['E1 collection service']);
  });
});

describe('flattenLeaves / neighbours', () => {
  test('flattenLeaves walks folders depth-first into leaf routes', () => {
    const design = group('Design')?.nodes ?? [];
    expect(flattenLeaves(design).map((l) => l.route)).toEqual([
      '/docs/design/architecture',
      '/docs/design/sessions/2026-09-27-indicators',
      '/docs/design/sessions/2026-09-29-brand',
    ]);
  });

  test('page-turn steps through the group leaves, crossing into a nested folder', () => {
    expect(neighbours(tree, '/docs/design/architecture').next?.route).toBe(
      '/docs/design/sessions/2026-09-27-indicators',
    );
    expect(neighbours(tree, '/no/such/route')).toEqual({});
  });
});
