# Component reference

The **spec** side of the Build track (D22): one page per built module — what it *is*,
precisely, by lookup. Where the [Field Manual](../../../field-manual/README.md) *teaches* the
concepts as a narrative (with scars), this reference *states* each component's
responsibilities, boundaries, and configuration. Read the architecture
[overview](../README.md) first for how they fit together.

Rule of thumb: if it has a plot and a scar, it's the Field Manual; if it's something you'd
look up, it's here.

## The modules

- **[core](core.md)** — the shared domain: market-event types, injectable clocks, the
  value objects every service speaks.
- **[ig-client](ig-client.md)** — the IG broker library behind vendor-neutral seams:
  session, REST, and Lightstreamer streaming.
- **[market-data-service](market-data-service.md)** — the capture service: ingest pipeline,
  store seam, single-instance lock, and the T5 resilience belt.

Each page cross-links the Field Manual chapter that teaches its concepts.
