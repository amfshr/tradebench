import { useEffect, useState } from 'react';

import { parseBoard, type ParsedBoard } from './board';
import { docByRoute } from './content';

/** Loads and parses board.md; returns the parsed board plus the raw markdown fallback. */
export function useBoard(): { board?: ParsedBoard; raw?: string } {
  const [state, setState] = useState<{ board?: ParsedBoard; raw?: string }>({});
  useEffect(() => {
    let live = true;
    docByRoute
      .get('/board')
      ?.load()
      .then((raw) => {
        if (live) {
          setState({ board: parseBoard(raw), raw });
        }
      });
    return () => {
      live = false;
    };
  }, []);
  return state;
}
