/**
 * Theme preference: system (default), light, or dark. An explicit choice sets
 * data-theme on <html> and persists; "system" clears both. A themechange event lets
 * canvas-ish renderers (mermaid) re-render — CSS tokens react on their own.
 */
export type ThemePref = 'system' | 'light' | 'dark';

const KEY = 'tradebench-theme';

export function storedPref(): ThemePref {
  const value = localStorage.getItem(KEY);
  return value === 'light' || value === 'dark' ? value : 'system';
}

export function nextPref(pref: ThemePref): ThemePref {
  return pref === 'system' ? 'light' : pref === 'light' ? 'dark' : 'system';
}

export function applyPref(pref: ThemePref): void {
  if (pref === 'system') {
    delete document.documentElement.dataset.theme;
    localStorage.removeItem(KEY);
  } else {
    document.documentElement.dataset.theme = pref;
    localStorage.setItem(KEY, pref);
  }
  window.dispatchEvent(new Event('themechange'));
}

export function initTheme(): void {
  const pref = storedPref();
  if (pref !== 'system') {
    document.documentElement.dataset.theme = pref;
  }
}

export function effectiveTheme(): 'light' | 'dark' {
  const explicit = document.documentElement.dataset.theme;
  if (explicit === 'light' || explicit === 'dark') {
    return explicit;
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}
