import { useEffect, useId, useState } from 'react';

import { effectiveTheme } from './theme';

/**
 * Renders a ```mermaid fence client-side (library lazy-loads on first diagram);
 * re-renders on theme toggle; click opens a full-screen lightbox for dense diagrams.
 */
export function Mermaid({ chart }: { chart: string }) {
  const [svg, setSvg] = useState<string>();
  const [error, setError] = useState<string>();
  const [zoom, setZoom] = useState(false);
  const [themeTick, setThemeTick] = useState(0);
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');

  useEffect(() => {
    const onTheme = () => setThemeTick((t) => t + 1);
    window.addEventListener('themechange', onTheme);
    return () => window.removeEventListener('themechange', onTheme);
  }, []);

  useEffect(() => {
    let live = true;
    import('mermaid').then(async ({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        theme: effectiveTheme() === 'dark' ? 'dark' : 'default',
      });
      try {
        const rendered = await mermaid.render(`mmd${id}${themeTick}`, chart);
        if (live) {
          setSvg(rendered.svg);
        }
      } catch (e) {
        if (live) {
          setError(String(e));
        }
      }
    });
    return () => {
      live = false;
    };
  }, [chart, id, themeTick]);

  useEffect(() => {
    if (!zoom) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setZoom(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [zoom]);

  if (error) {
    return (
      <pre className="mermaid-error">
        <code>{chart}</code>
      </pre>
    );
  }
  if (!svg) {
    return <div className="mermaid-loading">rendering diagram…</div>;
  }
  return (
    <>
      <div
        className="mermaid-diagram"
        title="Click to enlarge"
        onClick={() => setZoom(true)}
        dangerouslySetInnerHTML={{ __html: svg }}
      />
      {zoom && (
        <div className="lightbox" onClick={() => setZoom(false)}>
          <div className="lightbox-inner" dangerouslySetInnerHTML={{ __html: svg }} />
          <p className="lightbox-hint">click anywhere or press Esc to close</p>
        </div>
      )}
    </>
  );
}
