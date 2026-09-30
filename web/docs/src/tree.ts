/** Pure sidebar/tree logic — kept glob-free so it tests as plain functions. */
import { routeFor } from './links';

/** A node is a folder (has children) or a leaf doc (has a route); never both. */
export interface TreeNode {
  label: string;
  route?: string;
  children?: TreeNode[];
}

export interface TreeGroup {
  label: string;
  nodes: TreeNode[];
}

/** '/docs/field-manual/', '' and '/' all address the docs map at '/docs'. */
export function normalizeRoute(pathname: string): string {
  const trimmed = pathname.replace(/\/+$/, '');
  return trimmed === '' ? '/docs' : trimmed;
}

const titleCase = (s: string) => {
  const w = s.replace(/-/g, ' ');
  return w.charAt(0).toUpperCase() + w.slice(1);
};

/**
 * '04-the-pump-…' → '4 · The pump …'; '2026-09-27-indicators' → '2026-09-27 · Indicators';
 * 'sessions' → 'Sessions'. Date prefixes are kept whole (not read as a chapter number).
 */
function prettyName(segment: string): string {
  const dated = segment.match(/^(\d{4}-\d{2}-\d{2})-(.*)$/);
  if (dated) {
    return `${dated[1]} · ${titleCase(dated[2])}`;
  }
  const numbered = segment.match(/^(\d{1,2})-(.*)$/);
  return numbered ? `${Number(numbered[1])} · ${titleCase(numbered[2])}` : titleCase(segment);
}

/** Insert a doc into the nested node tree by its path segments below the group root. */
function insert(nodes: TreeNode[], segments: string[], route: string): void {
  const [head, ...rest] = segments;
  if (rest.length === 0) {
    nodes.push({ label: head === 'README' ? 'Index' : prettyName(head), route });
    return;
  }
  let folder = nodes.find((n) => n.children && n.label === prettyName(head));
  if (!folder) {
    folder = { label: prettyName(head), children: [] };
    nodes.push(folder);
  }
  insert(folder.children!, rest, route);
}

/** Files before folders; the board leaf leads its group; numeric-aware within each. Recursive. */
function sortNodes(nodes: TreeNode[]): void {
  nodes.sort((a, b) => {
    const aBoard = a.route === '/board' ? 0 : 1;
    const bBoard = b.route === '/board' ? 0 : 1;
    if (aBoard !== bBoard) {
      return aBoard - bBoard;
    }
    const aFolder = a.children ? 1 : 0;
    const bFolder = b.children ? 1 : 0;
    if (aFolder !== bFolder) {
      return aFolder - bFolder;
    }
    return a.label.localeCompare(b.label, 'en', { numeric: true });
  });
  for (const n of nodes) {
    if (n.children) {
      sortNodes(n.children);
    }
  }
}

/** Leaf docs in depth-first display order — the page-turn sequence. */
export function flattenLeaves(nodes: TreeNode[]): TreeNode[] {
  return nodes.flatMap((n) => (n.route ? [n] : flattenLeaves(n.children ?? [])));
}

/** Previous/next among a group's leaves (depth-first), for the page-turn. */
export function neighbours(
  groups: TreeGroup[],
  route: string,
): { prev?: TreeNode; next?: TreeNode } {
  for (const group of groups) {
    const leaves = flattenLeaves(group.nodes);
    const index = leaves.findIndex((l) => l.route === route);
    if (index !== -1) {
      return { prev: leaves[index - 1], next: leaves[index + 1] };
    }
  }
  return {};
}

interface GroupSpec {
  label: string;
  root: string;
  only?: string;
}

const GROUPS: GroupSpec[] = [
  { label: 'Product', root: 'docs/product/' },
  { label: 'Field Manual', root: 'docs/field-manual/' },
  { label: 'Design', root: 'docs/design/' },
  { label: 'Decisions', root: 'docs/', only: 'docs/decisions.md' },
  { label: 'Reference', root: 'docs/reference/' },
  { label: 'Inherited (read-only)', root: 'docs/inherited/' },
  { label: 'Board', root: '.claude/tasks/' },
];

/** Pure: repo paths in, nested sidebar groups out. */
export function buildTree(repoPaths: string[]): TreeGroup[] {
  return GROUPS.map((spec) => {
    const nodes: TreeNode[] = [];
    const members = repoPaths.filter((p) =>
      spec.only ? p === spec.only : p.startsWith(spec.root) && p !== 'docs/README.md',
    );
    for (const p of members) {
      const segments = p.slice(spec.root.length).replace(/\.md$/, '').split('/');
      insert(nodes, segments, routeFor(p));
    }
    sortNodes(nodes);
    return { label: spec.label, nodes };
  }).filter((g) => g.nodes.length > 0);
}
