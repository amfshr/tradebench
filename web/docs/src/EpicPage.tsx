import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';

import { historyForEpic, type Epic, type Ticket } from './board';
import { epicDocByNum, ticketRoute } from './content';
import { MarkdownBody } from './MarkdownBody';
import { StatusChip } from './StatusChip';
import { useBoard } from './useBoard';
import type { ParsedBoard } from './board';

function MetaRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="meta-row">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function EpicAside({ epic, board }: { epic: Epic; board: ParsedBoard }) {
  const open = epic.tickets.filter((t) => t.status !== 'done').length;
  const history = historyForEpic(board, epic.id);
  return (
    <aside className="epic-aside">
      <dl className="meta-panel">
        <MetaRow label="since">{epic.since || '—'}</MetaRow>
        <MetaRow label="updated">{epic.updated || '—'}</MetaRow>
        <MetaRow label="open">{open}</MetaRow>
        <MetaRow label="done">
          {epic.done} / {epic.total}
        </MetaRow>
        <MetaRow label="book">
          {epic.book && epic.book !== '—' ? (
            <Link to="/docs/book">{epic.book}</Link>
          ) : (
            '—'
          )}
        </MetaRow>
      </dl>

      {epic.decisions.length > 0 && (
        <div className="aside-block">
          <p className="section-label">Decisions</p>
          <ul className="aside-list">
            {epic.decisions.map((d) => (
              <li key={d}>
                <Link to="/docs/decisions">{d}</Link>
              </li>
            ))}
          </ul>
        </div>
      )}

      {history.length > 0 && (
        <div className="aside-block">
          <p className="section-label">History</p>
          <ol className="timeline">
            {history.map((h, i) => (
              <li key={i}>
                <span className="timeline-date">{h.date}</span>
                <span className="timeline-title">{h.title}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
    </aside>
  );
}

function TicketTable({ tickets, epicNum }: { tickets: Ticket[]; epicNum: number }) {
  return (
    <table className="ticket-table">
      <tbody>
        {tickets.map((t) => {
          const route = ticketRoute(epicNum, t.id);
          const title = t.title ? <b>{t.title}</b> : null;
          return (
            <tr key={t.id} className={`row-${t.status}`}>
              <td className="tcell-id">{t.id}</td>
              <td className="tcell-title">
                {route ? <Link to={route}>{title ?? t.summary}</Link> : <>{title} {t.summary}</>}
              </td>
              <td className="tcell-status">
                <StatusChip status={t.status} />
              </td>
            </tr>
          );
        })}
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

      <div className="epic-cols">
        <div className="epic-main">
          {open.length > 0 && (
            <section>
              <p className="section-label">Open</p>
              <TicketTable tickets={open} epicNum={epic.num} />
            </section>
          )}
          {done.length > 0 && (
            <section>
              <p className="section-label">Done</p>
              <TicketTable tickets={done} epicNum={epic.num} />
            </section>
          )}
          {plan && (
            <section className="epic-plan">
              <p className="section-label">Full plan</p>
              <MarkdownBody markdown={plan} repoPath={doc?.repoPath ?? ''} />
            </section>
          )}
        </div>
        <EpicAside epic={epic} board={board} />
      </div>
    </div>
  );
}
