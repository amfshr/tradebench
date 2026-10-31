# Grafana dashboards (E1-T16)

The collector's two dashboards, kept as code. They live in the Grafana Cloud stack's **Tradebench** folder.

| File | Dashboard | For |
|---|---|---|
| `collector-operations.json` | Collector — operations | The evening check: alive, the belt, outbound calls, the runtime, warnings and errors |
| `collector-domain.json` | Collector — domain | Ticks and bars per market and user, gaps by origin, the heal, job runs, the tick-gap rhythm (B24) |

**Trends only.** The record views — the coverage map, gap lists, the catalogue — belong to the platform app,
which reads Postgres (D25 R3). These dashboards answer "is it healthy, and how is it trending".

**The contract.** The `tradebench_*` series the panels query are the collector's metrics
(`market-data-service/…/metrics/CollectorMetrics.java`), under the names Grafana Cloud stores them by.
`DashboardsTest` fails the build if a panel queries a series the collector does not emit, calls an unknown
function, or names a data source or an instance of its own (G1: both are personal to the stack).

**Changing one.** Edit the JSON here, run the build, then push it to the stack under the same `uid` — through the
Grafana MCP server (`update_dashboard`, folder `tradebench`, overwrite), or by importing the file in Grafana's
UI. Edits made only in Grafana's UI are lost at the next push: export them back to this file first.

The data sources are dashboard variables (`$prom`, `$logs`), and `$instance` picks the collector.
