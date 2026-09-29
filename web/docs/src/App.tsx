import { Route, Routes } from 'react-router-dom';

import { BoardPage } from './BoardPage';
import { EpicPage } from './EpicPage';
import { HomePage } from './HomePage';
import { MarkdownPage } from './MarkdownPage';
import { Sidebar } from './Sidebar';

export function App() {
  return (
    <div className="layout">
      <Sidebar />
      <main className="content">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/board" element={<BoardPage />} />
          <Route path="/board/epics/:slug" element={<EpicPage />} />
          <Route path="/*" element={<MarkdownPage />} />
        </Routes>
      </main>
    </div>
  );
}
