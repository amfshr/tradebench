import { useEffect, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';

import { Search } from './Search';
import { applyPref, nextPref, storedPref, type ThemePref } from './theme';

const THEME_ICON: Record<ThemePref, string> = { system: '◐', light: '☀', dark: '☾' };
const GITHUB = 'https://github.com/amfshr/tradebench';

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
      <div className="topnav-left">
        <Link to="/" className="topnav-wordmark">
          tradebench<span className="topnav-cursor" />
        </Link>
        <span className="topnav-tag">docs</span>
      </div>

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

      <div className="topnav-right">
        <button type="button" className="nav-search" onClick={() => setSearchOpen(true)}>
          <span>Search</span>
          <kbd>⌘K</kbd>
        </button>
        <ThemeToggle />
      </div>

      <Search open={searchOpen} onClose={() => setSearchOpen(false)} />
    </header>
  );
}
