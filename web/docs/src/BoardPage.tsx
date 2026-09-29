import { Link } from 'react-router-dom';

import { allTickets, type Epic } from './board';
import { epicDocByNum } from './content';
import { StatusChip } from './StatusChip';
import { useBoard } from './useBoard';

function epicRoute(num: number): string | undefined {
  return epicDocByNum.get(num)?.route;
}

function EpicCard({ epic }: { epic: Epic }) {
  const route = epicRoute(epic.num);
  const pct = epic.total ? Math.round((epic.done / epic.total) * 100) : 0;
  const inner = (
    <>
      <div className="epic-card-top">
        <span className="epic-id">
          {epic.emoji} {epic.id}
        </span>
        <span className="epic-progress-label">
          {epic.done}/{epic.total}
        </span>
      </div>
      <h3 className="epic-card-name">{epic.name}</h3>
      {epic.mission && <p className="epic-card-mission">{epic.mission}</p>}
      <div className="epic-bar">
        <span style={{ width: `${pct}%` }} />
      </div>
    </>
  );
  return route ? (
    <Link to={route} className="epic-card epic-card-link">
      {inner}
    </Link>
  ) : (
    <div className="epic-card">{inner}</div>
  );
}

export function BoardPage() {
  const { board, raw } = useBoard();

  if (!board) {
    return <article className="page page-loading">loading…</article>;
  }
  if (board.epics.length === 0) {
    return (
      <article className="page">
        <pre>{raw}</pre>
      </article>
    );
  }

  const tickets = allTickets(board);
  const inProgress = tickets.filter((t) => t.status === 'in-progress').length;
  const done = tickets.filter((t) => t.status === 'done').length;
  const queued = tickets.filter((t) => t.status === 'queued').length;

  return (
    <div className="board">
      <header className="board-head">
        <h1>Board</h1>
        <p className="board-sub">
          What is being built, what is next, what was cut. Quiet is healthy — only active
          work asks for attention.
        </p>
        <p className="board-stats">
          <b>{inProgress}</b> in progress · <b>{queued}</b> queued · <b>{done}</b> done
        </p>
      </header>

      <section>
        <p className="section-label">Epics</p>
        <div className="epic-grid">
          {board.epics.map((e) => (
            <EpicCard key={e.id} epic={e} />
          ))}
        </div>
      </section>

      <div className="board-cols">
        <section>
          <p className="section-label">Tickets · attention first</p>
          <table className="ticket-table">
            <tbody>
              {tickets.map((t) => {
                const route = epicRoute(Number(t.epicId.slice(1)));
                return (
                  <tr key={`${t.epicId}-${t.id}`} className={`row-${t.status}`}>
                    <td className="tcell-id">{t.id}</td>
                    <td className="tcell-title">{t.title || t.summary}</td>
                    <td className="tcell-epic">
                      {route ? <Link to={route}>{t.epicId}</Link> : t.epicId}
                    </td>
                    <td className="tcell-status">
                      <StatusChip status={t.status} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>

        <section>
          <p className="section-label">Done · history</p>
          <ol className="timeline">
            {board.history.map((h, i) => (
              <li key={i}>
                <span className="timeline-date">{h.date}</span>
                <span className="timeline-title">{h.title}</span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </div>
  );
}
