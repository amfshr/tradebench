# Tradebench

[![CI](https://github.com/amfshr/tradebench/actions/workflows/ci.yml/badge.svg)](https://github.com/amfshr/tradebench/actions/workflows/ci.yml)

**The bench you work at** — a multi-user web platform covering the whole algorithmic-trading
pipeline: collect and store market data, explore it visually with custom indicators, define
strategies in a DSL, backtest them visually or as jobs, and promote proven models to automated
trading bots with risk management and results analytics.

**Backtest-first, live-last.** The product exists to compress the loop between idea and verdict:
run a strategy over years of data and learn it doesn't work *before* caring about a live trade.

- **Product truth:** [`docs/inherited/product-requirements.md`](docs/inherited/product-requirements.md)
  — the PRD (vision, principles P1–P10, platform surface, phasing).
- **Evidence base:** [`docs/inherited/`](docs/inherited/) — the complete seed pack from the
  Python prototype era ([reading order](docs/inherited/seed-pack.md)): the IG broker playbook,
  the engineering playbook, the decisions triage, and the five grill-session records the PRD was
  calibrated from.
- **Work:** [`.claude/tasks/board.md`](.claude/tasks/board.md) — the board (single source of
  truth for epics/tickets).

**Stack:** Java 21+ / Spring Boot services · React/TypeScript frontend (this monorepo) ·
PostgreSQL · Redis Streams (provisional) · Docker · staging + prod, promoted by release tag.

**Status:** building. Epic 1 — the 24/7 market-data collection service — is in progress
(foundations + IG session/REST core merged; streaming and persistence next).

*Predecessor: a private Python prototype (2026) that ran live on IG demo; Tradebench inherits
its lessons, not its code. Strategy definitions and credentials never enter this repository.*
