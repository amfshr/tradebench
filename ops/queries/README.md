# Operator queries

Versioned, reusable SQL for inspecting Tradebench databases — run from DataGrip (attach
this directory to the DataGrip workbench project) or via the `db-admin` agent. Read-only
by convention: schema changes are Flyway migrations through a PR, never hand DDL (the
drift gate catches divergence); one-off throwaway exploration belongs in query consoles,
not here. Add a file when a query earns reuse.
