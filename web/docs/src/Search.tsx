import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { searchDocs } from './docSearch';

/** ⌘K command palette: fuzzy-search every page, arrow to move, Enter to open. */
export function Search({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const results = useMemo(() => searchDocs(query), [query]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      inputRef.current?.focus();
    }
  }, [open]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  if (!open) {
    return null;
  }

  const go = (route: string) => {
    navigate(route);
    onClose();
  };

  return (
    <div className="search-overlay" onClick={onClose}>
      <div className="search-box" onClick={(e) => e.stopPropagation()}>
        <input
          ref={inputRef}
          className="search-input"
          placeholder="Search pages…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault();
              setActive((a) => Math.min(a + 1, results.length - 1));
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              setActive((a) => Math.max(a - 1, 0));
            } else if (e.key === 'Enter' && results[active]) {
              go(results[active].route);
            } else if (e.key === 'Escape') {
              onClose();
            }
          }}
        />
        <ul className="search-results">
          {results.length === 0 && <li className="search-empty">No pages match.</li>}
          {results.map((r, i) => (
            <li key={r.route}>
              <button
                type="button"
                className={i === active ? 'search-hit active' : 'search-hit'}
                onMouseEnter={() => setActive(i)}
                onClick={() => go(r.route)}
              >
                <span className="search-hit-label">{r.label}</span>
                <span className="search-hit-group">{r.group}</span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
