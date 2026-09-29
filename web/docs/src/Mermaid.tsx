import { useEffect, useId, useState } from 'react';

import { effectiveTheme } from './theme';

/** Brand theme for mermaid, read from the live CSS tokens so it tracks D19/brand.md. */
function brandTheme() {
  const s = getComputedStyle(document.documentElement);
  const t = (name: string) => s.getPropertyValue(name).trim();
  const bg = t('--bg');
  const surface = t('--bg-raised');
  const fg = t('--fg');
  const muted = t('--fg-muted');
  const accent = t('--accent');
  const border = t('--border');
  const mono = t('--font-mono') || 'monospace';
  return {
    theme: 'base' as const,
    themeVariables: {
      darkMode: effectiveTheme() === 'dark',
      background: bg,
      fontFamily: mono,
      fontSize: '13px',
      // flowchart nodes: neutral surface, quiet border, accent kept for edges/emphasis
      primaryColor: surface,
      mainBkg: surface,
      primaryTextColor: fg,
      primaryBorderColor: border,
      nodeBorder: border,
      nodeTextColor: fg,
      lineColor: muted,
      secondaryColor: bg,
      tertiaryColor: bg,
      clusterBkg: bg,
      clusterBorder: border,
      titleColor: fg,
      edgeLabelBackground: bg,
      // sequence diagrams
      actorBkg: surface,
      actorBorder: accent,
      actorTextColor: fg,
      actorLineColor: border,
      signalColor: muted,
      signalTextColor: fg,
      labelBoxBkgColor: surface,
      labelBoxBorderColor: border,
      labelTextColor: fg,
      loopTextColor: muted,
      noteBkgColor: surface,
      noteBorderColor: accent,
      noteTextColor: fg,
      // state diagrams
      transitionColor: muted,
      transitionLabelColor: fg,
      stateBkg: surface,
      stateBorder: border,
      compositeBackground: bg,
      compositeBorder: border,
      altBackground: bg,
    },
  };
}

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
    import('mermaid')
      .then(async ({ default: mermaid }) => {
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'strict',
          flowchart: { curve: 'basis', padding: 14 },
          ...brandTheme(),
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
      })
      .catch((e: unknown) => {
        // A failed chunk load (stale deploy, offline) must not hang on "rendering…".
        if (live) {
          setError(String(e));
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
