import {
  isValidElement,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import { Link, useLocation } from 'react-router-dom';
import rehypeHighlight from 'rehype-highlight';
import rehypeSlug from 'rehype-slug';
import remarkGfm from 'remark-gfm';

import { docByRoute, repoPathExists } from './content';
import { Mermaid } from './Mermaid';
import { PageFooter } from './PageFooter';
import { Toc } from './Toc';
import { Topbar } from './Topbar';
import { resolveLink } from './links';
import { extractToc } from './outline';
import { normalizeRoute } from './tree';

function textOf(children: ReactNode): string {
  if (typeof children === 'string' || typeof children === 'number') {
    return String(children);
  }
  if (Array.isArray(children)) {
    return children.map(textOf).join('');
  }
  if (isValidElement(children)) {
    return textOf((children.props as { children?: ReactNode }).children);
  }
  return '';
}

function CopyablePre({ children }: { children: ReactNode }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="codeblock">
      <button
        type="button"
        className="copy-button"
        onClick={() => {
          void navigator.clipboard.writeText(textOf(children));
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        }}
      >
        {copied ? '✓ copied' : 'copy'}
      </button>
      <pre>{children}</pre>
    </div>
  );
}

function heading(Tag: 'h2' | 'h3') {
  return function Heading({ id, children }: { id?: string; children?: ReactNode }) {
    return (
      <Tag id={id}>
        {children}
        {id && (
          <a className="hanchor" href={`#${id}`} aria-label="link to this section">
            #
          </a>
        )}
      </Tag>
    );
  };
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
    pre({ children }) {
      const only = Array.isArray(children) ? children[0] : children;
      if (
        isValidElement(only) &&
        String((only.props as { className?: string }).className ?? '').includes('language-mermaid')
      ) {
        return <>{children}</>;
      }
      return <CopyablePre>{children}</CopyablePre>;
    },
    h2: heading('h2'),
    h3: heading('h3'),
  };
}

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
          <ReactMarkdown
            remarkPlugins={[remarkGfm]}
            rehypePlugins={[rehypeSlug, rehypeHighlight]}
            components={componentsFor(doc.repoPath)}
          >
            {markdown}
          </ReactMarkdown>
          <PageFooter route={route} />
        </article>
        <Toc entries={toc} />
      </div>
    </>
  );
}
