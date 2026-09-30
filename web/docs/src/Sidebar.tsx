import { Link, useLocation } from 'react-router-dom';

import { sidebarTree as tree } from './content';
import { normalizeRoute, type TreeNode } from './tree';

const OPEN_BY_DEFAULT = new Set(['Product', 'Field Manual', 'Board']);

function containsRoute(node: TreeNode, current: string): boolean {
  if (node.route) {
    return node.route === current;
  }
  return (node.children ?? []).some((c) => containsRoute(c, current));
}

function NodeList({ nodes, current }: { nodes: TreeNode[]; current: string }) {
  return (
    <ul>
      {nodes.map((node) =>
        node.route ? (
          <li key={node.route}>
            <Link to={node.route} className={node.route === current ? 'active' : undefined}>
              {node.label}
            </Link>
          </li>
        ) : (
          <li key={node.label}>
            <details open={(node.children ?? []).some((c) => containsRoute(c, current))}>
              <summary className="tree-folder">{node.label}</summary>
              <NodeList nodes={node.children ?? []} current={current} />
            </details>
          </li>
        ),
      )}
    </ul>
  );
}

export function Sidebar() {
  const current = normalizeRoute(useLocation().pathname);
  return (
    <nav className="sidebar">
      {tree.map((group) => {
        const active = group.nodes.some((n) => containsRoute(n, current));
        return (
          <details key={`${group.label}:${active}`} open={active || OPEN_BY_DEFAULT.has(group.label)}>
            <summary>{group.label}</summary>
            <NodeList nodes={group.nodes} current={current} />
          </details>
        );
      })}
    </nav>
  );
}
