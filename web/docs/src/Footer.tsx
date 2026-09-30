const LICENSE_URL = 'https://github.com/amfshr/tradebench/blob/main/LICENSE.md';

/** Site-wide licence footnote (D23) — every page carries the copyright + licence. */
export function Footer() {
  return (
    <footer className="site-footer">
      © 2026 Alexander Fisher · source-available under the{' '}
      <a href={LICENSE_URL} target="_blank" rel="noreferrer">
        PolyForm Noncommercial License 1.0.0
      </a>{' '}
      — non-commercial use only, all commercial rights reserved.
    </footer>
  );
}
