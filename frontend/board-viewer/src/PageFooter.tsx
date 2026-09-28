import { Link } from 'react-router-dom';

import { sidebarTree } from './content';
import { neighbours } from './tree';

/** Previous/next cards within the current sidebar group — the book's page-turn. */
export function PageFooter({ route }: { route: string }) {
  const { prev, next } = neighbours(sidebarTree, route);
  if (!prev && !next) {
    return null;
  }
  return (
    <nav className="page-turn">
      {prev ? (
        <Link className="turn-card turn-prev" to={prev.route}>
          <span className="turn-hint">← Previous</span>
          <span className="turn-label">{prev.label}</span>
        </Link>
      ) : (
        <span />
      )}
      {next ? (
        <Link className="turn-card turn-next" to={next.route}>
          <span className="turn-hint">Next →</span>
          <span className="turn-label">{next.label}</span>
        </Link>
      ) : (
        <span />
      )}
    </nav>
  );
}
