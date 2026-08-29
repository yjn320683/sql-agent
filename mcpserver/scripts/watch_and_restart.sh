#!/bin/bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

load_env_file
ensure_host_log_dir
print_deploy_context

CONTAINER_NAME="sql-agent-mcp-server"
MONITOR_LOG_FILE="${HOST_LOG_DIR}/monitor.log"

exec >> "${MONITOR_LOG_FILE}" 2>&1

is_container_running() {
  local container_id
  container_id="$(docker ps -q -f "name=^${CONTAINER_NAME}$" 2>/dev/null || true)"
  [[ -n "${container_id}" ]]
}

echo "=============================="
echo "监控执行时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "开始监控服务状态..."
echo "容器名称: ${CONTAINER_NAME}"

if is_container_running; then
  echo "服务仍在运行，本次无需拉起。"
  exit 0
fi

echo
echo "检测到服务已停止，开始重新拉起..."
run_compose up -d

echo
echo "服务已拉起，当前容器状态："
run_compose ps
