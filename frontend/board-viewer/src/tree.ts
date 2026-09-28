/** Pure sidebar/tree logic — kept glob-free so it tests as plain functions. */
import { routeFor } from './links';

export interface TreeItem {
  route: string;
  label: string;
}

export interface TreeGroup {
  label: string;
  items: TreeItem[];
}

/** '/docs/book/', '' and '/' all address the docs map at '/docs'. */
export function normalizeRoute(pathname: string): string {
  const trimmed = pathname.replace(/\/+$/, '');
  return trimmed === '' ? '/docs' : trimmed;
}

/** '04-the-pump-and-ack-after-apply' → '4 · The pump and ack after apply'. */
function prettyName(basename: string): string {
  const numbered = basename.match(/^(\d+)-(.*)$/);
  const words = (numbered ? numbered[2] : basename).replace(/-/g, ' ');
  const capitalised = words.charAt(0).toUpperCase() + words.slice(1);
  return numbered ? `${Number(numbered[1])} · ${capitalised}` : capitalised;
}

function itemFor(repoPath: string, groupRoot: string): TreeItem {
  const rel = repoPath.slice(groupRoot.length).replace(/\.md$/, '');
  const slash = rel.lastIndexOf('/');
  const dir = slash === -1 ? '' : rel.slice(0, slash);
  const basename = slash === -1 ? rel : rel.slice(slash + 1);
  const name = basename === 'README' ? 'Index' : prettyName(basename);
  return { route: routeFor(repoPath), label: dir === '' ? name : `${dir}/${name}` };
}

interface GroupSpec {
  label: string;
  root: string;
  only?: string;
}

const GROUPS: GroupSpec[] = [
  { label: 'Product', root: 'docs/product/' },
  { label: 'The book', root: 'docs/book/' },
  { label: 'Design', root: 'docs/design/' },
  { label: 'Design sessions', root: 'docs/design-sessions/' },
  { label: 'Decisions', root: 'docs/', only: 'docs/decisions.md' },
  { label: 'Reference', root: 'docs/reference/' },
  { label: 'Inherited (read-only)', root: 'docs/inherited/' },
  { label: 'Board', root: '.claude/tasks/' },
];

/** Pure: repo paths in, sidebar groups out. Board first inside its group; book in chapter order. */
export function buildTree(repoPaths: string[]): TreeGroup[] {
  return GROUPS.map((spec) => {
    const members = repoPaths
      .filter((p) =>
        spec.only ? p === spec.only : p.startsWith(spec.root) && p !== 'docs/README.md',
      )
      .sort((a, b) => {
        if (spec.root === '.claude/tasks/') {
          const aBoard = a === '.claude/tasks/board.md' ? 0 : 1;
          const bBoard = b === '.claude/tasks/board.md' ? 0 : 1;
          if (aBoard !== bBoard) {
            return aBoard - bBoard;
          }
        }
        return a.localeCompare(b, 'en', { numeric: true });
      });
    return { label: spec.label, items: members.map((p) => itemFor(p, spec.root)) };
  }).filter((g) => g.items.length > 0);
}
