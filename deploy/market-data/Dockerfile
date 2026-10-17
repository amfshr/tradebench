# syntax=docker/dockerfile:1.7
# The collector's image (E1-T7, D34 R7): the Temurin 25 JRE, the app as Gradle's installDist built with
# -PduckdbPlatform for the target CPU, and the OpenTelemetry Java agent pinned by checksum. Built from the
# repository root; .dockerignore admits only the distribution and the entrypoint — nothing else, no .env.
FROM eclipse-temurin:25-jre

ARG OTEL_AGENT_VERSION=2.32.0
ARG OTEL_AGENT_SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
ADD --checksum=sha256:${OTEL_AGENT_SHA256} \
    https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar \
    /opt/otel/opentelemetry-javaagent.jar

RUN useradd --system --uid 10001 --no-create-home tradebench && chmod 0644 /opt/otel/opentelemetry-javaagent.jar
COPY market-data-service/build/install/market-data-service /opt/market-data-service
COPY --chmod=0755 deploy/market-data/entrypoint.sh /opt/entrypoint.sh

USER tradebench
ENTRYPOINT ["/opt/entrypoint.sh"]
