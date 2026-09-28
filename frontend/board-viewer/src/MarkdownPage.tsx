import { useEffect, useState, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import { Link, useLocation } from 'react-router-dom';
import rehypeHighlight from 'rehype-highlight';
import rehypeSlug from 'rehype-slug';
import remarkGfm from 'remark-gfm';

import { docByRoute, repoPathExists } from './content';
import { Mermaid } from './Mermaid';
import { resolveLink } from './links';
import { normalizeRoute } from './tree';

function textOf(children: ReactNode): string {
  if (typeof children === 'string') {
    return children;
  }
  if (Array.isArray(children)) {
    return children.map(textOf).join('');
  }
  return '';
}

function componentsFor(fromRepoPath: string): Components {
  return {
    a({ href, children }) {
      const resolved = resolveLink(fromRepoPath, href ?? '', repoPathExists);
      if (resolved.kind === 'external') {
        return (
          <a href={resolved.href} target="_blank" rel="noreferrer">
            {children}
          </a>
        );
      }
      if (resolved.kind === 'anchor') {
        return <a href={resolved.to}>{children}</a>;
      }
      return <Link to={resolved.to}>{children}</Link>;
    },
    code({ className, children }) {
      if (className?.includes('language-mermaid')) {
        return <Mermaid chart={textOf(children)} />;
      }
      return <code className={className}>{children}</code>;
    },
  };
}

export function MarkdownPage() {
  const { pathname, hash } = useLocation();
  const route = normalizeRoute(pathname);
  const doc = docByRoute.get(route);
  const [markdown, setMarkdown] = useState<string>();

  useEffect(() => {
    setMarkdown(undefined);
    let live = true;
    doc?.load().then((text) => {
      if (live) {
        setMarkdown(text);
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
  if (markdown === undefined) {
    return <article className="page page-loading">loading…</article>;
  }
  return (
    <article className="page">
      <p className="page-path">{doc.repoPath}</p>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeSlug, rehypeHighlight]}
        components={componentsFor(doc.repoPath)}
      >
        {markdown}
      </ReactMarkdown>
    </article>
  );
}
