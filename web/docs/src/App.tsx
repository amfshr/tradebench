import { Route, Routes } from 'react-router-dom';

import { BoardPage } from './BoardPage';
import { DecisionsPage } from './DecisionsPage';
import { EpicPage } from './EpicPage';
import { HomePage } from './HomePage';
import { MarkdownPage } from './MarkdownPage';
import { Sidebar } from './Sidebar';
import { TicketPage } from './TicketPage';
import { TopNav } from './TopNav';

export function App() {
  return (
    <div className="app">
      <TopNav />
      <div className="layout">
        <Sidebar />
        <main className="content">
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/docs/decisions" element={<DecisionsPage />} />
            <Route path="/board" element={<BoardPage />} />
            <Route path="/board/epics/:slug" element={<EpicPage />} />
            <Route path="/board/epics/:slug/:ticket" element={<TicketPage />} />
            <Route path="/*" element={<MarkdownPage />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}
