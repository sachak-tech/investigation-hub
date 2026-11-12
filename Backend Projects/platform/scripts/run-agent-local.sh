#!/usr/bin/env bash
set -euo pipefail
platform_dir="$(cd "$(dirname "$0")/.." && pwd)"
project_dir="$(cd "$platform_dir/../.." && pwd)"
: "${IH_DB_PASSWORD:?Set IH_DB_PASSWORD before starting the agent service}"
agent_python="${IH_AGENT_PYTHON:-$project_dir/.cache/agent-venv/bin/python}"
if [[ ! -x "$agent_python" ]]; then
  echo "Create the agent Python 3.13 environment first; see agent-service/README.md." >&2
  exit 1
fi
exec "$agent_python" -m uvicorn agent.app:app --app-dir "$platform_dir/agent-service" \
  --host 127.0.0.1 --port "${IH_AGENT_PORT:-18085}"
