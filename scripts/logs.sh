#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=scripts/common.sh
source "${SCRIPT_DIR}/common.sh"
load_env_file
ensure_host_dirs

case "${1:-all}" in
  backend)
    tail -f -n 200 "$(backend_log_file)"
    ;;
  agent)
    run_compose logs -f --tail=200 agent
    ;;
  datacompare)
    echo "验数模块运行在 Backend 进程中，以下显示 Backend 日志。"
    tail -f -n 200 "$(backend_log_file)"
    ;;
  all)
    echo "===== backend 最近日志 ====="
    tail -n 200 "$(backend_log_file)"
    echo "===== agent 实时日志 ====="
    run_compose logs -f --tail=200 agent
    ;;
  *)
    echo "用法: bash scripts/logs.sh [all|backend|agent|datacompare]"
    exit 2
    ;;
esac
