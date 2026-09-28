import { Fragment } from 'react';
import { Link } from 'react-router-dom';

import { repoPathExists } from './content';
import { routeFor } from './links';

const GITHUB_BLOB = 'https://github.com/amfshr/tradebench/blob/main/';

/** Breadcrumbs from the repo path (directories link where a README gives them a page). */
export function Topbar({ repoPath }: { repoPath: string }) {
  const segments = repoPath.split('/');
  return (
    <header className="topbar">
      <nav className="crumbs">
        {segments.map((segment, i) => {
          const last = i === segments.length - 1;
          const readme = `${segments.slice(0, i + 1).join('/')}/README.md`;
          const crumb =
            !last && repoPathExists(readme) ? (
              <Link to={routeFor(readme)}>{segment}</Link>
            ) : (
              <span className={last ? 'crumb-here' : undefined}>{segment}</span>
            );
          return (
            <Fragment key={i}>
              {i > 0 && <span className="crumb-sep">/</span>}
              {crumb}
            </Fragment>
          );
        })}
      </nav>
      <a className="topbar-github" href={GITHUB_BLOB + repoPath} target="_blank" rel="noreferrer">
        View on GitHub ↗
      </a>
    </header>
  );
}
