import { useEffect, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';

import type { Ticket } from './board';
import { epicDocByNum } from './content';
import { MarkdownBody } from './MarkdownBody';
import { StatusChip } from './StatusChip';
import { prLink, splitDoD, ticketSection, type TicketSection } from './ticketDetail';
import { useBoard } from './useBoard';

function MetaRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="meta-row">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

export function TicketPage() {
  const { slug = '', ticket = '' } = useParams();
  const num = Number(slug.match(/^e(\d+)-/)?.[1] ?? NaN);
  const { board } = useBoard();
  const doc = epicDocByNum.get(num);
  const [section, setSection] = useState<TicketSection | null>(null);

  useEffect(() => {
    let live = true;
    doc?.load().then((md) => {
      if (live) {
        setSection(ticketSection(md, ticket));
      }
    });
    return () => {
      live = false;
    };
  }, [doc, ticket]);

  if (!board) {
    return <article className="page page-loading">loading…</article>;
  }
  const epic = board.epics.find((e) => e.num === num);
  const row: Ticket | undefined = epic?.tickets.find((t) => t.id === ticket);
  if (!epic || !row) {
    return (
      <article className="page">
        <h1>Unknown ticket</h1>
        <p>
          No ticket <code>{ticket}</code> under <Link to="/board">the board</Link>.
        </p>
      </article>
    );
  }

  const { what, dod } = splitDoD(row.summary);
  const pr = prLink(row.note);
  const meta = section?.meta ?? {};

  return (
    <div className="ticket-page">
      <nav className="crumbs">
        <Link to="/board">board</Link>
        <span className="crumb-sep">/</span>
        <Link to={doc?.route ?? '/board'}>{epic.id}</Link>
        <span className="crumb-sep">/</span>
        <span className="crumb-here">{row.id}</span>
      </nav>

      <header className="ticket-head">
        <div className="ticket-eyebrow">
          <StatusChip status={row.status} />
          <span className="ticket-epic-tag">
            {epic.emoji} {epic.name}
          </span>
        </div>
        <h1>{row.title || row.id}</h1>
        {what && <p className="ticket-what">{what}</p>}
      </header>

      <div className="ticket-cols">
        <div className="ticket-main">
          {dod.length > 0 && (
            <section>
              <p className="section-label">Done when</p>
              <ul className="donewhen">
                {dod.map((item, i) => (
                  <li key={i} className={row.status === 'done' ? 'checked' : undefined}>
                    <span className="donewhen-box">{row.status === 'done' ? '✓' : ''}</span>
                    {item}
                  </li>
                ))}
              </ul>
            </section>
          )}
          {section?.body && (
            <section className="ticket-plan">
              <p className="section-label">Plan</p>
              <MarkdownBody markdown={section.body} repoPath={doc?.repoPath ?? ''} />
            </section>
          )}
        </div>

        <aside className="epic-aside">
          <dl className="meta-panel">
            <MetaRow label="epic">
              <Link to={doc?.route ?? '/board'}>{epic.id}</Link>
            </MetaRow>
            <MetaRow label="status">{row.status.replace('-', ' ')}</MetaRow>
            {meta.type && <MetaRow label="type">{meta.type}</MetaRow>}
            {meta.branch && <MetaRow label="branch">{meta.branch}</MetaRow>}
            {meta.started && <MetaRow label="started">{meta.started}</MetaRow>}
            {meta.blockedBy && <MetaRow label="blocked by">{meta.blockedBy}</MetaRow>}
            {pr && (
              <MetaRow label="pr">
                <a href={pr} target="_blank" rel="noreferrer">
                  {pr.match(/\/(\d+)$/)?.[1]}
                </a>
              </MetaRow>
            )}
          </dl>
        </aside>
      </div>
    </div>
  );
}
