import { useEffect, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';

import { Search } from './Search';
import { applyPref, nextPref, storedPref, type ThemePref } from './theme';

const THEME_ICON: Record<ThemePref, string> = { system: '◐', light: '☀', dark: '☾' };
const GITHUB = 'https://github.com/amfshr/tradebench';

/** The two-candles mark (R5) — filled steel + hollow accent. */
function Mark() {
  return (
    <svg className="topnav-mark" viewBox="0 0 16 16" width="18" height="18" aria-hidden="true">
      <rect x="3.6" y="1.5" width="1.4" height="12" rx="0.7" fill="var(--fg-muted)" />
      <rect x="1.5" y="4.5" width="5.6" height="7" rx="1" fill="var(--fg-muted)" />
      <rect x="11.3" y="3" width="1.4" height="11.5" rx="0.7" fill="var(--accent)" />
      <rect
        x="9.6"
        y="6.3"
        width="4.8"
        height="5.6"
        rx="1"
        fill="none"
        stroke="var(--accent)"
        strokeWidth="1.5"
      />
    </svg>
  );
}

function ThemeToggle() {
  const [pref, setPref] = useState<ThemePref>(() => storedPref());
  return (
    <button
      type="button"
      className="nav-icon"
      title={`Theme: ${pref} (click to cycle)`}
      onClick={() => {
        const next = nextPref(pref);
        applyPref(next);
        setPref(next);
      }}
    >
      {THEME_ICON[pref]}
    </button>
  );
}

export function TopNav() {
  const [searchOpen, setSearchOpen] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <header className="topnav">
      <Link to="/" className="topnav-left">
        <Mark />
        <span className="topnav-wordmark">
          tradebench<span className="topnav-cursor" />
        </span>
        <span className="topnav-tag">docs</span>
      </Link>

      <nav className="topnav-links">
        <NavLink to="/docs" className={({ isActive }) => (isActive ? 'active' : undefined)}>
          Docs
        </NavLink>
        <NavLink to="/docs/book" className={({ isActive }) => (isActive ? 'active' : undefined)}>
          Book
        </NavLink>
        <NavLink to="/board" className={({ isActive }) => (isActive ? 'active' : undefined)}>
          Board
        </NavLink>
        <a href={GITHUB} target="_blank" rel="noreferrer">
          GitHub
        </a>
      </nav>

      <button type="button" className="nav-search" onClick={() => setSearchOpen(true)}>
        <span>Search</span>
        <kbd>⌘K</kbd>
      </button>
      <ThemeToggle />

      <Search open={searchOpen} onClose={() => setSearchOpen(false)} />
    </header>
  );
}
