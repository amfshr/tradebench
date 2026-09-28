/**
 * Pure link resolution: a markdown href, as written in a repo doc, becomes an app route,
 * an in-page anchor, or an external URL. The write model's relative links (`../design/…`,
 * `04-the-pump….md#anchor`) must keep working in the read model unchanged.
 */

export type ResolvedLink =
  | { kind: 'internal'; to: string }
  | { kind: 'anchor'; to: string }
  | { kind: 'external'; href: string };

export const GITHUB_BLOB = 'https://github.com/amfshr/tradebench/blob/main/';

/** Repo path → app route. README.md collapses onto its directory; board paths get /board. */
export function routeFor(repoPath: string): string {
  if (repoPath === '.claude/tasks/board.md') {
    return '/board';
  }
  const epic = repoPath.match(/^\.claude\/tasks\/epics\/(.+)\.md$/);
  if (epic) {
    return `/board/epics/${epic[1]}`;
  }
  return `/${repoPath.replace(/\.md$/, '').replace(/\/README$/, '')}`;
}

/** Resolve `.` and `..` against the directory of `fromRepoPath`. */
function resolveRepoPath(fromRepoPath: string, relative: string): string {
  const base = relative.startsWith('/') ? [] : fromRepoPath.split('/').slice(0, -1);
  for (const segment of relative.replace(/^\//, '').split('/')) {
    if (segment === '' || segment === '.') {
      continue;
    }
    if (segment === '..') {
      base.pop();
    } else {
      base.push(segment);
    }
  }
  return base.join('/');
}

export function resolveLink(
  fromRepoPath: string,
  href: string,
  exists: (repoPath: string) => boolean,
): ResolvedLink {
  if (/^[a-z][a-z0-9+.-]*:/i.test(href)) {
    return { kind: 'external', href };
  }
  const hashIndex = href.indexOf('#');
  const path = hashIndex === -1 ? href : href.slice(0, hashIndex);
  const hash = hashIndex === -1 ? '' : href.slice(hashIndex);
  if (path === '') {
    return { kind: 'anchor', to: hash };
  }
  const repoPath = resolveRepoPath(fromRepoPath, path);
  if (repoPath.endsWith('.md') && exists(repoPath)) {
    return { kind: 'internal', to: routeFor(repoPath) + hash };
  }
  return { kind: 'external', href: GITHUB_BLOB + repoPath };
}
