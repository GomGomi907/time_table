#!/usr/bin/env sh
set -eu

export BACKEND_PORT="${BACKEND_PORT:-8081}"
export SERVER_PORT="$BACKEND_PORT"
export PORT="${PORT:-8080}"
export HOSTNAME="${HOSTNAME:-0.0.0.0}"

java -jar /app/backend/app.jar &
backend_pid="$!"

cleanup() {
  kill "$backend_pid" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

backend_ready=0
for _ in $(seq 1 45); do
  if curl -fsS "http://127.0.0.1:${BACKEND_PORT}/actuator/health" >/dev/null 2>&1; then
    backend_ready=1
    break
  fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    wait "$backend_pid"
    exit $?
  fi
  sleep 1
done

if [ "$backend_ready" -ne 1 ]; then
  echo "Backend did not become healthy on port ${BACKEND_PORT}" >&2
  exit 1
fi

cd /app/frontend
exec node server.js
