import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import type { Ticket } from './board';
import { epicDocByNum } from './content';
import { MarkdownBody } from './MarkdownBody';
import { StatusChip } from './StatusChip';
import { useBoard } from './useBoard';

function TicketTable({ tickets }: { tickets: Ticket[] }) {
  return (
    <table className="ticket-table">
      <tbody>
        {tickets.map((t) => (
          <tr key={t.id} className={`row-${t.status}`}>
            <td className="tcell-id">{t.id}</td>
            <td className="tcell-title">
              {t.title && <b>{t.title}</b>} {t.summary}
            </td>
            <td className="tcell-status">
              <StatusChip status={t.status} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function EpicPage() {
  const { slug = '' } = useParams();
  const num = Number(slug.match(/^e(\d+)-/)?.[1] ?? NaN);
  const { board } = useBoard();
  const doc = epicDocByNum.get(num);
  const [plan, setPlan] = useState<string>();

  useEffect(() => {
    let live = true;
    doc?.load().then((t) => live && setPlan(t));
    return () => {
      live = false;
    };
  }, [doc]);

  if (!board) {
    return <article className="page page-loading">loading…</article>;
  }
  const epic = board.epics.find((e) => e.num === num);
  if (!epic) {
    return (
      <article className="page">
        <h1>Unknown epic</h1>
        <p>
          No epic <code>{slug}</code> on the <Link to="/board">board</Link>.
        </p>
      </article>
    );
  }

  const open = epic.tickets.filter((t) => t.status !== 'done');
  const done = epic.tickets.filter((t) => t.status === 'done');
  const pct = epic.total ? Math.round((epic.done / epic.total) * 100) : 0;

  return (
    <div className="epic-page">
      <nav className="crumbs">
        <Link to="/board">board</Link>
        <span className="crumb-sep">/</span>
        <span className="crumb-here">{epic.id}</span>
      </nav>

      <header className="epic-head">
        <p className="epic-eyebrow">
          {epic.emoji} {epic.id} · {epic.statusLabel}
        </p>
        <h1>{epic.name}</h1>
        {epic.mission && <p className="epic-mission">{epic.mission}</p>}
        <div className="epic-bar epic-bar-wide">
          <span style={{ width: `${pct}%` }} />
        </div>
        <p className="epic-progress-label">
          {epic.done} / {epic.total} done · {open.length} open
        </p>
      </header>

      {open.length > 0 && (
        <section>
          <p className="section-label">Open</p>
          <TicketTable tickets={open} />
        </section>
      )}
      {done.length > 0 && (
        <section>
          <p className="section-label">Done</p>
          <TicketTable tickets={done} />
        </section>
      )}

      {plan && (
        <section className="epic-plan">
          <p className="section-label">Full plan</p>
          <MarkdownBody markdown={plan} repoPath={doc?.repoPath ?? ''} />
        </section>
      )}
    </div>
  );
}
