import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// fs.allow reaches the repo root: the dev server serves the repo's own markdown
// (docs/, .claude/tasks/) as modules — the app has no backend (E2-T1 nod N2).
export default defineConfig({
  plugins: [react()],
  server: { fs: { allow: ['../..'] } },
});
