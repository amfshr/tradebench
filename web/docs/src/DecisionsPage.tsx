import { useEffect, useState } from 'react';

import { docByRoute } from './content';
import { MarkdownBody } from './MarkdownBody';
import { parseDecisionLog, type DecisionEntry } from './decisions';

const STATUS_CLASS: Record<string, string> = {
  Accepted: 'dstatus-accepted',
  Provisional: 'dstatus-open',
  Open: 'dstatus-open',
  Superseded: 'dstatus-superseded',
};

export function DecisionsPage() {
  const [entries, setEntries] = useState<DecisionEntry[]>();
  const [raw, setRaw] = useState<string>();

  useEffect(() => {
    let live = true;
    docByRoute
      .get('/docs/decisions')
      ?.load()
      .then((md) => {
        if (live) {
          setRaw(md);
          setEntries(parseDecisionLog(md));
        }
      });
    return () => {
      live = false;
    };
  }, []);

  if (!entries) {
    return <article className="page page-loading">loading…</article>;
  }
  if (entries.length === 0) {
    return (
      <article className="page">
        <pre>{raw}</pre>
      </article>
    );
  }

  const newestFirst = [...entries].reverse();
  const accepted = entries.filter((e) => e.status === 'Accepted').length;

  return (
    <div className="decisions-page">
      <header className="board-head">
        <h1>Decisions</h1>
        <p className="board-sub">
          One entry per real decision — the choice, the why, and its status. Newest first;
          never rewritten, only superseded.
        </p>
        <p className="board-stats">
          <b>{entries.length}</b> decisions · <b>{accepted}</b> accepted
        </p>
      </header>

      {newestFirst.map((d) => (
        <section key={d.id} id={d.id.toLowerCase()} className="dcard">
          <div className="dcard-head">
            <span className="dcard-id">{d.id}</span>
            <span className={`chip ${STATUS_CLASS[d.status] ?? ''}`}>{d.status}</span>
            <span className="dcard-date">{d.date}</span>
          </div>
          <h3 className="dcard-title">{d.title}</h3>
          <div className="dcard-body">
            <MarkdownBody markdown={d.body} repoPath="docs/decisions.md" />
          </div>
        </section>
      ))}
    </div>
  );
}
