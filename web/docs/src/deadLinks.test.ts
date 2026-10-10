/**
 * Every relative markdown link in the repo's docs and board must point at a file that exists.
 * The app's resolver (links.ts) sends anything it does not serve to GitHub, so a dead link
 * renders as a working-looking link to a 404 — this is the check that catches it, against the
 * files on disk rather than the app's idea of them (E1-T10 #25). Inline links and images only;
 * the docs use no reference-style definitions.
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const TREES = ['docs', '.claude/tasks'];
const SINGLES = ['README.md', 'CLAUDE.md'];

function markdownFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return name === 'node_modules' ? [] : markdownFiles(path);
    }
    return path.endsWith('.md') ? [path] : [];
  });
}

/** The targets of `[text](href)` and `![alt](href "title")`, minus schemes, bare anchors and fragments. */
export function relativeLinkTargets(markdown: string): string[] {
  return [...markdown.matchAll(/\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)]
    .map((m) => m[1])
    .filter((href) => !/^[a-z][a-z0-9+.-]*:/i.test(href) && !href.startsWith('#'))
    .map((href) => href.split('#')[0])
    .filter((path) => path !== '');
}

describe('dead links', () => {
  test('link targets are read as written, without schemes, anchors or fragments', () => {
    const md = '[a](x.md) [b](../y.md#part) [c](https://ig.com/x) [d](#here) ![e](img/f.png "t") [g](mailto:x@y)';
    expect(relativeLinkTargets(md)).toEqual(['x.md', '../y.md', 'img/f.png']);
  });

  test('every relative link in docs/, the board, README and CLAUDE.md points at a file that exists', () => {
    const files = [
      ...TREES.flatMap((tree) => markdownFiles(join(ROOT, tree))),
      ...SINGLES.map((file) => join(ROOT, file)),
    ];
    const dead = files.flatMap((file) =>
      relativeLinkTargets(readFileSync(file, 'utf8'))
        .filter((href) => !existsSync(resolve(dirname(file), href)))
        .map((href) => `${relative(ROOT, file)} -> ${href}`),
    );

    expect(files.length).toBeGreaterThan(50); // the walk found the book, not an empty directory
    expect(dead).toEqual([]);
  });
});
