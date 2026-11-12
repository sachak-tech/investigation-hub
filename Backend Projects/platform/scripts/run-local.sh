#!/usr/bin/env bash
set -euo pipefail

platform_dir="$(cd "$(dirname "$0")/.." && pwd)"
project_dir="$(cd "$platform_dir/../.." && pwd)"
java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" && -x /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
  java_home=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "$java_home" ]]; then
  java_bin="$java_home/bin/java"
else
  java_bin="$(command -v java)"
fi
log_dir="$project_dir/.cache/platform-logs"
pids=()

: "${IH_DB_PASSWORD:?Set IH_DB_PASSWORD before running this script}"
db_base="jdbc:postgresql://${IH_DB_HOST:-localhost}:${IH_DB_PORT:-5432}/${IH_DB_NAME:-postgres}"
export IH_POLICY_DB_URL="${IH_POLICY_DB_URL:-$db_base?currentSchema=investigation_policy}"
export IH_CASE_DB_URL="${IH_CASE_DB_URL:-$db_base?currentSchema=investigation_case}"
export IH_ALERT_DB_URL="${IH_ALERT_DB_URL:-$db_base?currentSchema=investigation_alert}"
export IH_PLAYBOOK_DB_URL="${IH_PLAYBOOK_DB_URL:-$db_base?currentSchema=investigation_flowable,investigation_playbook}"
mkdir -p "$log_dir"

cleanup() {
  for pid in "${pids[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in "${pids[@]}"; do
    wait "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

for port in 18081 18082 18083 18084; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $port is already in use; stop the existing platform service first." >&2
    exit 1
  fi
done

cd "$platform_dir"
if [[ -n "$java_home" ]]; then
  JAVA_HOME="$java_home" mvn -q -DskipTests package
else
  mvn -q -DskipTests package
fi
PGPASSWORD="$IH_DB_PASSWORD" psql -v ON_ERROR_STOP=1 \
  -h "${IH_DB_HOST:-localhost}" -p "${IH_DB_PORT:-5432}" \
  -U "${IH_DB_USER:-postgres}" -d "${IH_DB_NAME:-postgres}" \
  -f scripts/create-local-schemas.sql >/dev/null

for service in policy playbook case alert; do
  IH_DB_PASSWORD="$IH_DB_PASSWORD" IH_DEMO_TASK_COMPLETION_ENABLED=true "$java_bin" \
    -jar "$platform_dir/$service-service/target/$service-service-0.1.0.jar" \
    > "$log_dir/$service.log" 2>&1 &
  pids+=("$!")
done

for port in 18081 18082 18083 18084; do
  ready=false
  for attempt in $(seq 1 60); do
    if curl --max-time 2 -fsS "http://127.0.0.1:$port/actuator/health/readiness" 2>/dev/null \
       | rg -q '"status":"UP"'; then
      ready=true
      break
    fi
    sleep 1
  done
  if [ "$ready" != true ]; then
    echo "Service on port $port did not become ready. Check $log_dir/*.log" >&2
    exit 1
  fi
done

echo "All four services are ready."
echo "Swagger: http://localhost:18081/swagger-ui.html (Policy)"
echo "         http://localhost:18082/swagger-ui.html (Case)"
echo "         http://localhost:18083/swagger-ui.html (Playbook)"
echo "         http://localhost:18084/swagger-ui.html (Alert)"

if [ "${1:-}" = "--smoke" ]; then
  python3 smoke.py
  exit
fi

echo "Run python3 smoke.py in another terminal. Press Ctrl+C here to stop all four services."
while true; do
  for pid in "${pids[@]}"; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "A service stopped; check $log_dir/*.log" >&2
      exit 1
    fi
  done
  sleep 1
done
