import { describe, expect, test } from 'vitest';

import { extractToc } from './outline';

describe('extractToc', () => {
  test('h2 and h3 make the outline; h1 does not appear but still counts for duplicates', () => {
    const md = ['# The scars', '', '## The jargon', '', '### Deep dive', '', '## The scars'].join(
      '\n',
    );
    expect(extractToc(md)).toEqual([
      { level: 2, text: 'The jargon', id: 'the-jargon' },
      { level: 3, text: 'Deep dive', id: 'deep-dive' },
      { level: 2, text: 'The scars', id: 'the-scars-1' },
    ]);
  });

  test('inline markdown is stripped so slugs match the rendered heading text', () => {
    expect(extractToc('## The `CaptureStore` seam')).toEqual([
      { level: 2, text: 'The CaptureStore seam', id: 'the-capturestore-seam' },
    ]);
  });

  test('heading-shaped lines inside code fences are not headings', () => {
    const md = ['## Real heading', '', '```bash', '## not a heading', '```', ''].join('\n');
    expect(extractToc(md)).toEqual([{ level: 2, text: 'Real heading', id: 'real-heading' }]);
  });
});
