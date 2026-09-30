/**
 * Pure "on this page" extraction. Slugs must match what rehype-slug gives the rendered
 * headings, so this mirrors it: one GithubSlugger per document, fed EVERY heading level
 * (duplicate counters include h1), emitting only h2/h3 for the outline.
 */
import GithubSlugger from 'github-slugger';

export interface TocEntry {
  level: 2 | 3;
  text: string;
  id: string;
}

export function extractToc(markdown: string): TocEntry[] {
  const slugger = new GithubSlugger();
  const entries: TocEntry[] = [];
  let inFence = false;
  for (const line of markdown.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) {
      continue;
    }
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (!heading) {
      continue;
    }
    const text = heading[2]
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/[`*_]/g, '')
      .trim();
    const id = slugger.slug(text);
    const level = heading[1].length;
    if (level === 2 || level === 3) {
      entries.push({ level, text, id });
    }
  }
  return entries;
}
