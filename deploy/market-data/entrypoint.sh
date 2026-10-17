#!/bin/sh
# The collector's entrypoint (E1-T7). The OpenTelemetry agent rides only when an OTLP endpoint is configured
# (in-ticket ruling 5): a local run with no Grafana stays quiet instead of failing an export every few seconds.
set -eu
if [ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ]; then
    export JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar ${JAVA_TOOL_OPTIONS:-}"
    # the telemetry names the instance the collector's own lines do — one name, set once
    export OTEL_RESOURCE_ATTRIBUTES="service.instance.id=${TRADEBENCH_INSTANCE:?}${OTEL_RESOURCE_ATTRIBUTES:+,$OTEL_RESOURCE_ATTRIBUTES}"
fi
exec /opt/market-data-service/bin/market-data-service "$@"
