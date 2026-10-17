#!/bin/sh
# Runs once, on the Postgres container's first start (docker-entrypoint-initdb.d), as the instance's superuser:
# creates the market_data database's three roles from the passwords in the container's environment. One
# transaction: all three roles or none. If it fails, the image never runs it again on that volume — see the
# runbook §1 (remove the volume, fix postgres.env, start again).
set -eu
: "${TRADEBENCH_DB_OWNER_PASSWORD:?}" "${TRADEBENCH_DB_APP_PASSWORD:?}" "${TRADEBENCH_DB_READER_PASSWORD:?}"
psql -v ON_ERROR_STOP=1 --single-transaction --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$POSTGRES_DB" \
    -v owner_password="$TRADEBENCH_DB_OWNER_PASSWORD" \
    -v app_password="$TRADEBENCH_DB_APP_PASSWORD" \
    -v reader_password="$TRADEBENCH_DB_READER_PASSWORD" \
    -f /opt/tradebench/roles.sql
