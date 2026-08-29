#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"
load_env_file
ensure_host_dirs
if ! run_compose ps --status running agent | grep -F 'sql-agent-agent' >/dev/null; then
  echo "Agent 未运行，开始拉起。"
  run_compose up -d
fi
if ! is_backend_running; then
  echo "Backend 未运行，开始拉起。"
  start_backend
fi
run_compose ps
print_backend_status
