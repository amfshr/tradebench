import type { TicketStatus } from './board';

const LABEL: Record<TicketStatus, string> = {
  'in-progress': 'in progress',
  queued: 'queued',
  iced: 'iced',
  done: 'done',
};

/** Only in-progress carries the accent dot; settled states stay quiet (P9). */
export function StatusChip({ status }: { status: TicketStatus }) {
  return (
    <span className={`chip chip-${status}`}>
      {status === 'in-progress' && <span className="chip-dot" />}
      {LABEL[status]}
    </span>
  );
}
