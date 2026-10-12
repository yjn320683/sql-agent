#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MIGRATE=false
if [[ "${1:-}" == "--migrate" ]]; then
  MIGRATE=true
elif [[ $# -gt 0 ]]; then
  echo "用法: $0 [--migrate]"
  exit 2
fi
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"
load_env_file
if [[ "${MIGRATE}" == "true" ]]; then
  "${SCRIPT_DIR}/db_migrate.sh" migrate
else
  "${SCRIPT_DIR}/db_migrate.sh" validate
fi
ensure_host_dirs
print_deploy_context
update_latest_code
if backend_running_from_build_jar; then
  echo "当前 Backend 直接运行构建产物，先迁移到独立运行副本，避免构建时覆盖正在加载的 JAR。"
  restart_backend
fi
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
