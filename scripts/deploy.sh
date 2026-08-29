#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"
load_env_file
ensure_host_dirs
print_deploy_context
update_latest_code
echo "并行构建 agent 镜像与 front/backend..."
run_compose build agent &
agent_build_pid=$!
if ! build_application; then
  wait "${agent_build_pid}" || true
  exit 1
fi
wait "${agent_build_pid}"
run_compose up -d --no-build
wait_agent_ready
restart_backend
run_compose ps
print_backend_status
