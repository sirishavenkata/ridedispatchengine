#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run-local.sh  — start infra with Docker, run the app locally with plain Java
# Use this if you want fast iteration without rebuilding Docker images.
# ─────────────────────────────────────────────────────────────────────────────
set -e

JAR="$(dirname "$0")/ride-dispatch.jar"

if [ ! -f "$JAR" ]; then
  echo "ERROR: ride-dispatch.jar not found. Run build.sh first."
  exit 1
fi

echo "Starting PostgreSQL and Redis via Docker..."
docker compose up -d postgres redis

echo "Waiting for services to be healthy..."
sleep 5

echo "Starting application..."
DB_URL="jdbc:postgresql://localhost:5432/dispatch_db" \
DB_USER="dispatch_user" \
DB_PASS="dispatch_pass" \
REDIS_HOST="localhost" \
REDIS_PORT="6379" \
SERVER_PORT="8080" \
java -jar "$JAR"
