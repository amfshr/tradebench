import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { epicStatus, historyForEpic, ticketDate, type Epic, type ParsedBoard, type Ticket } from './board';
import { docByRoute, epicDocByNum, ticketRoute } from './content';
import { parseDecisions, type DecisionEntry } from './decisions';
import { GITHUB_BLOB } from './links';
import { StatusChip } from './StatusChip';
import { useBoard } from './useBoard';

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
              <td className="tcell-date">{ticketDate(t.note)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function EpicAside({
  epic,
  board,
  decisions,
}: {
  epic: Epic;
  board: ParsedBoard;
  decisions: Map<string, DecisionEntry>;
}) {
  const open = epic.tickets.filter((t) => t.status !== 'done').length;
  const history = historyForEpic(board, epic.id);
  return (
    <aside className="epic-aside">
      <dl className="meta-panel">
        <div className="meta-row">
          <dt>since</dt>
          <dd>{epic.since || '—'}</dd>
        </div>
        <div className="meta-row">
          <dt>updated</dt>
          <dd>{epic.updated || '—'}</dd>
        </div>
        <div className="meta-row">
          <dt>open</dt>
          <dd>{open}</dd>
        </div>
        <div className="meta-row">
          <dt>book</dt>
          <dd>{epic.book && epic.book !== '—' ? <Link to="/docs/book">{epic.book}</Link> : '—'}</dd>
        </div>
      </dl>

      {epic.decisions.length > 0 && (
        <div className="aside-block">
          <p className="section-label">Decisions</p>
          <ul className="decision-list">
            {epic.decisions.map((id) => {
              const d = decisions.get(id);
              return (
                <li key={id}>
                  <Link to="/docs/decisions">{id}</Link>
                  {d && <span className="decision-title"> · {d.title}</span>}
                  {d && <span className="decision-date">{d.date}</span>}
                </li>
              );
            })}
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

export function EpicPage() {
  const { slug = '' } = useParams();
  const num = Number(slug.match(/^e(\d+)-/)?.[1] ?? NaN);
  const { board } = useBoard();
  const [decisions, setDecisions] = useState<Map<string, DecisionEntry>>(new Map());

  useEffect(() => {
    let live = true;
    docByRoute
      .get('/docs/decisions')
      ?.load()
      .then((md) => {
        if (live) {
          setDecisions(parseDecisions(md));
        }
      });
    return () => {
      live = false;
    };
  }, []);

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
  const planRoute = epicDocByNum.get(num)?.route;

  return (
    <div className="epic-page">
      <nav className="crumbs">
        <Link to="/board">board</Link>
        <span className="crumb-sep">/</span>
        <span className="crumb-here">{epic.id}</span>
      </nav>

      <header className="epic-head">
        <div className="epic-eyebrow">
          <span className="chip chip-id">
            {epic.emoji} {epic.id}
          </span>
          <StatusChip status={epicStatus(epic)} />
        </div>
        <h1>{epic.name}</h1>
        <div className="epic-progress">
          <div className="epic-bar epic-bar-wide">
            <span style={{ width: `${pct}%` }} />
          </div>
          <span className="epic-progress-label">
            {epic.done} / {epic.total} done
          </span>
        </div>
      </header>

      {epic.mission && (
        <section className="why-epic">
          <p className="section-label">Why this epic</p>
          <p className="why-text">{epic.mission}</p>
        </section>
      )}

      <div className="epic-cols">
        <div className="epic-main">
          {open.length > 0 && (
            <section>
              <p className="section-label">
                Open <span className="section-count">{open.length}</span>
              </p>
              <TicketTable tickets={open} epicNum={epic.num} />
            </section>
          )}
          {done.length > 0 && (
            <section>
              <p className="section-label">
                Done <span className="section-count">{done.length}</span>
              </p>
              <TicketTable tickets={done} epicNum={epic.num} />
            </section>
          )}
          {planRoute && (
            <p className="epic-planlink">
              <a
                href={`${GITHUB_BLOB}${epicDocByNum.get(num)?.repoPath}`}
                target="_blank"
                rel="noreferrer"
              >
                View the full plan file on GitHub ↗
              </a>
            </p>
          )}
        </div>
        <EpicAside epic={epic} board={board} decisions={decisions} />
      </div>
    </div>
  );
}
