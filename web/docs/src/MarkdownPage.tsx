import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';

import { docByRoute } from './content';
import { MarkdownBody } from './MarkdownBody';
import { PageFooter } from './PageFooter';
import { Toc } from './Toc';
import { Topbar } from './Topbar';
import { extractToc } from './outline';
import { normalizeRoute } from './tree';

export function MarkdownPage() {
  const { pathname, hash } = useLocation();
  const route = normalizeRoute(pathname);
  const doc = docByRoute.get(route);
  const [markdown, setMarkdown] = useState<string>();
  const [loadError, setLoadError] = useState<string>();

  useEffect(() => {
    setMarkdown(undefined);
    setLoadError(undefined);
    let live = true;
    doc
      ?.load()
      .then((text) => {
        if (live) {
          setMarkdown(text);
        }
      })
      .catch((e: unknown) => {
        if (live) {
          setLoadError(String(e));
        }
      });
    return () => {
      live = false;
    };
  }, [doc]);

  useEffect(() => {
    if (markdown === undefined) {
      return;
    }
    if (hash) {
      document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView();
    } else {
      window.scrollTo(0, 0);
    }
  }, [markdown, hash]);

  const toc = useMemo(() => extractToc(markdown ?? ''), [markdown]);

  if (!doc) {
    return (
      <article className="page">
        <h1>Not found</h1>
        <p>
          No document at <code>{route}</code>. Start from the <Link to="/docs">docs map</Link>.
        </p>
      </article>
    );
  }
  if (loadError !== undefined) {
    return (
      <article className="page">
        <h1>Failed to load</h1>
        <p>
          <code>{doc.repoPath}</code> did not load — a stale deploy or dropped connection is
          the usual cause. Reload the page; if it persists: <code>{loadError}</code>
        </p>
      </article>
    );
  }
  if (markdown === undefined) {
    return <article className="page page-loading">loading…</article>;
  }
  return (
    <>
      <Topbar repoPath={doc.repoPath} />
      <div className="page-wrap">
        <article className="page">
          <MarkdownBody markdown={markdown} repoPath={doc.repoPath} />
          <PageFooter route={route} />
        </article>
        <Toc entries={toc} />
      </div>
    </>
  );
}
