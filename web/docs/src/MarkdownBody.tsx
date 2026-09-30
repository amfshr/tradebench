import { isValidElement, useState, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import { Link } from 'react-router-dom';
import rehypeHighlight from 'rehype-highlight';
import rehypeSlug from 'rehype-slug';
import remarkGfm from 'remark-gfm';

import { repoPathExists } from './content';
import { Mermaid } from './Mermaid';
import { resolveLink } from './links';

export function textOf(children: ReactNode): string {
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
    blockquote({ children }) {
      // Scar callout (R9): a blockquote led by **Scar** renders as the mono-labelled card.
      if (/^\s*Scar\b/.test(textOf(children))) {
        return <div className="scar-card">{children}</div>;
      }
      return <blockquote>{children}</blockquote>;
    },
    h2: heading('h2'),
    h3: heading('h3'),
  };
}

/** The markdown renderer, shared by the doc reader and the epic-page plan. */
export function MarkdownBody({ markdown, repoPath }: { markdown: string; repoPath: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeSlug, rehypeHighlight]}
      components={componentsFor(repoPath)}
    >
      {markdown}
    </ReactMarkdown>
  );
}
