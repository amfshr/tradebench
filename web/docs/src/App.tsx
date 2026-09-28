import { Route, Routes } from 'react-router-dom';

import { MarkdownPage } from './MarkdownPage';
import { Sidebar } from './Sidebar';

export function App() {
  return (
    <div className="layout">
      <Sidebar />
      <main className="content">
        <Routes>
          <Route path="/*" element={<MarkdownPage />} />
        </Routes>
      </main>
    </div>
  );
}
