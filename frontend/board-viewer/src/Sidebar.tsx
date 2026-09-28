import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';

import { sidebarTree as tree } from './content';
import { applyPref, nextPref, storedPref, type ThemePref } from './theme';
import { normalizeRoute } from './tree';

const OPEN_BY_DEFAULT = new Set(['Product', 'The book', 'Board']);
const THEME_ICON: Record<ThemePref, string> = { system: '◐ auto', light: '☀ light', dark: '☾ dark' };

function ThemeToggle() {
  const [pref, setPref] = useState<ThemePref>(() => storedPref());
  return (
    <button
      type="button"
      className="theme-toggle"
      title="Toggle theme (system → light → dark)"
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

export function Sidebar() {
  const current = normalizeRoute(useLocation().pathname);
  return (
    <nav className="sidebar">
      <div className="sidebar-head">
        <Link to="/docs" className="sidebar-title">
          Tradebench
        </Link>
        <ThemeToggle />
      </div>
      <p className="sidebar-subtitle">docs &amp; board</p>
      {tree.map((group) => {
        const active = group.items.some((i) => i.route === current);
        return (
          <details key={`${group.label}:${active}`} open={active || OPEN_BY_DEFAULT.has(group.label)}>
            <summary>{group.label}</summary>
            <ul>
              {group.items.map((item) => (
                <li key={item.route}>
                  <Link to={item.route} className={item.route === current ? 'active' : undefined}>
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </details>
        );
      })}
    </nav>
  );
}
