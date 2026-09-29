import { Link } from 'react-router-dom';

import { docByRoute } from './content';

interface Section {
  n: string;
  route: string;
  title: string;
  blurb: string;
}

const SECTIONS: Section[] = [
  {
    n: '01',
    route: '/docs/product/overview',
    title: 'Product overview',
    blurb:
      'What Tradebench is, who it is for, and how the pipeline fits together: data → chart → strategy → backtest → bot.',
  },
  {
    n: '02',
    route: '/docs/book',
    title: 'The Book',
    blurb:
      'A teaching text written from scars. How the collection service works — sockets to Postgres and out again — and why each choice.',
  },
  {
    n: '03',
    route: '/docs/design/architecture',
    title: 'Architecture',
    blurb: 'System diagrams and the reasoning behind each boundary. Storage, compute, the seams.',
  },
  {
    n: '04',
    route: '/docs/decisions',
    title: 'Decisions',
    blurb: 'The record of choices made and the trade-offs accepted. Dated, numbered, never rewritten.',
  },
  {
    n: '05',
    route: '/board',
    title: 'Board',
    blurb: 'What is being built now, what is next, and what was cut.',
  },
];

export function HomePage() {
  const available = SECTIONS.filter((s) => docByRoute.has(s.route) || s.route === '/board');
  return (
    <div className="home">
      <header className="home-hero">
        <p className="home-wordmark">
          tradebench<span className="home-cursor" />
        </p>
        <h1>The bench you work at.</h1>
        <p className="home-lede">
          A private platform for the whole algorithmic-trading loop — collect data, chart it,
          write a strategy, test it against years of history, and only then let it trade. These
          docs are the manual, the book, and the record of how it was built.
        </p>
        <div className="home-cta">
          <Link className="home-cta-primary" to="/docs/product/overview">
            Start with the overview
          </Link>
          <Link className="home-cta-secondary" to="/docs/book">
            Read the book from chapter one
          </Link>
        </div>
      </header>

      <p className="section-label">Sections</p>
      <div className="home-grid">
        {available.map((s) => (
          <Link key={s.route} to={s.route} className="home-card">
            <span className="home-card-n">{s.n}</span>
            <h3>{s.title}</h3>
            <p>{s.blurb}</p>
            <span className="home-card-arrow">→</span>
          </Link>
        ))}
      </div>
    </div>
  );
}
