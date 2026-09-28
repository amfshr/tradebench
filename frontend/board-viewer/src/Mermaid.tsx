import { useEffect, useId, useState } from 'react';

/** Renders a ```mermaid fence client-side; the library loads lazily on first diagram. */
export function Mermaid({ chart }: { chart: string }) {
  const [svg, setSvg] = useState<string>();
  const [error, setError] = useState<string>();
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');

  useEffect(() => {
    let live = true;
    import('mermaid').then(async ({ default: mermaid }) => {
      const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      mermaid.initialize({ startOnLoad: false, theme: dark ? 'dark' : 'default' });
      try {
        const rendered = await mermaid.render(`mmd${id}`, chart);
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
  }, [chart, id]);

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
  return <div className="mermaid-diagram" dangerouslySetInnerHTML={{ __html: svg }} />;
}
