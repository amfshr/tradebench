import { useEffect, useState } from 'react';

import type { TocEntry } from './outline';

/** "On this page" — scroll-spied outline of the current document's h2/h3s. */
export function Toc({ entries }: { entries: TocEntry[] }) {
  const [active, setActive] = useState<string>();

  useEffect(() => {
    const headings = entries
      .map((e) => document.getElementById(e.id))
      .filter((el): el is HTMLElement => el !== null);
    if (headings.length === 0) {
      return;
    }
    const observer = new IntersectionObserver(
      (observed) => {
        const visible = observed.filter((o) => o.isIntersecting);
        if (visible.length > 0) {
          setActive(visible[0].target.id);
        }
      },
      { rootMargin: '0% 0% -70% 0%' },
    );
    headings.forEach((h) => observer.observe(h));
    return () => observer.disconnect();
  }, [entries]);

  if (entries.length < 2) {
    return null;
  }
  return (
    <aside className="toc">
      <p className="toc-title">On this page</p>
      <ul>
        {entries.map((entry) => (
          <li
            key={entry.id}
            className={`toc-lvl${entry.level}${entry.id === active ? ' active' : ''}`}
          >
            <a href={`#${entry.id}`}>{entry.text}</a>
          </li>
        ))}
      </ul>
    </aside>
  );
}
