#!/usr/bin/env sh
set -eu

export BACKEND_PORT="${BACKEND_PORT:-8081}"
export SERVER_PORT="$BACKEND_PORT"
export PORT="${PORT:-8080}"
# Cloud Run requires the ingress process to listen on all interfaces. Some
# runtimes set HOSTNAME to a container hostname, so do not preserve an inherited
# value here.
export HOSTNAME="0.0.0.0"

if [ "${APP_RELEASE_MODE:-}" = "beta" ] || [ "${APP_RELEASE_MODE:-}" = "production" ]; then
  case "${APP_DB_URL:-}" in
    ""|jdbc:h2:*|*":h2:"*)
      echo "Unsafe release runtime: APP_DB_URL must point to PostgreSQL/Cloud SQL." >&2
      exit 1
      ;;
  esac

  if [ "${APP_AUTH_MOCK_LOGIN_ENABLED:-false}" = "true" ]; then
    echo "Unsafe release runtime: mock login must be disabled." >&2
    exit 1
  fi

  if [ "${APP_SYNC_GOOGLE_MOCK_ENABLED:-false}" = "true" ]; then
    echo "Unsafe release runtime: mock Google sync must be disabled." >&2
    exit 1
  fi

  if [ "${APP_AI_ENABLED:-false}" = "true" ] && [ -z "${APP_GEMINI_API_KEY:-}" ]; then
    echo "Unsafe release runtime: APP_GEMINI_API_KEY is required when AI is enabled." >&2
    exit 1
  fi
fi

java -jar /app/backend/app.jar &
backend_pid="$!"
frontend_pid=""

cleanup() {
  kill "$backend_pid" 2>/dev/null || true
  if [ -n "$frontend_pid" ]; then
    kill "$frontend_pid" 2>/dev/null || true
  fi
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
node server.js &
frontend_pid="$!"

while :; do
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    backend_status=0
    wait "$backend_pid" || backend_status="$?"
    echo "Backend process exited; stopping Cloud Run container" >&2
    kill "$frontend_pid" 2>/dev/null || true
    wait "$frontend_pid" 2>/dev/null || true
    exit "$backend_status"
  fi

  if ! kill -0 "$frontend_pid" 2>/dev/null; then
    frontend_status=0
    wait "$frontend_pid" || frontend_status="$?"
    exit "$frontend_status"
  fi

  sleep 2
done
