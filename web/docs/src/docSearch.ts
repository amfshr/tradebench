import { sidebarTree } from './content';

export interface SearchItem {
  route: string;
  label: string;
  group: string;
}

/** Flat search corpus: every sidebar item, tagged with its group for context. */
export const searchIndex: SearchItem[] = sidebarTree.flatMap((g) =>
  g.items.map((i) => ({ route: i.route, label: i.label, group: g.label })),
);

/** Subsequence match (fuzzy): every query char appears in order. Scored by compactness. */
function score(query: string, text: string): number | null {
  const q = query.toLowerCase();
  const t = text.toLowerCase();
  if (q === '') {
    return 0;
  }
  let ti = 0;
  let first = -1;
  let last = -1;
  for (let qi = 0; qi < q.length; qi++) {
    const found = t.indexOf(q[qi], ti);
    if (found === -1) {
      return null;
    }
    if (first === -1) {
      first = found;
    }
    last = found;
    ti = found + 1;
  }
  // tighter spans and earlier starts rank higher (lower is better)
  return (last - first) * 2 + first;
}

export function searchDocs(query: string, limit = 12): SearchItem[] {
  if (query.trim() === '') {
    return searchIndex.slice(0, limit);
  }
  return searchIndex
    .map((item) => {
      const s = score(query, `${item.group} ${item.label}`);
      return s === null ? null : { item, s };
    })
    .filter((x): x is { item: SearchItem; s: number } => x !== null)
    .sort((a, b) => a.s - b.s)
    .slice(0, limit)
    .map((x) => x.item);
}
