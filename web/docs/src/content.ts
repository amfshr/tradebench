/**
 * The content model: the repo's markdown, globbed by the dev server (nod N2 — no
 * backend), keyed by repo path and addressable by route.
 */
import { routeFor } from './links';
import { buildTree } from './tree';

export interface Doc {
  repoPath: string;
  route: string;
  load: () => Promise<string>;
}

const PREFIX = '../../../';

const modules: Record<string, () => Promise<string>> = {
  ...(import.meta.glob('../../../docs/**/*.md', { query: '?raw', import: 'default' }) as Record<
    string,
    () => Promise<string>
  >),
  ...(import.meta.glob('../../../.claude/tasks/**/*.md', {
    query: '?raw',
    import: 'default',
  }) as Record<string, () => Promise<string>>),
};

export const docs: Doc[] = Object.entries(modules).map(([key, load]) => {
  const repoPath = key.slice(PREFIX.length);
  return { repoPath, route: routeFor(repoPath), load };
});

export const docByRoute = new Map(docs.map((d) => [d.route, d]));
export const repoPathExists = (repoPath: string): boolean =>
  docs.some((d) => d.repoPath === repoPath);
export const sidebarTree = buildTree(docs.map((d) => d.repoPath));
