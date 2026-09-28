import { Link, useLocation } from 'react-router-dom';

import { sidebarTree as tree } from './content';
import { normalizeRoute } from './tree';

const OPEN_BY_DEFAULT = new Set(['Product', 'The book', 'Board']);

export function Sidebar() {
  const current = normalizeRoute(useLocation().pathname);
  return (
    <nav className="sidebar">
      <Link to="/docs" className="sidebar-title">
        Tradebench
      </Link>
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
