#!/bin/bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

load_env_file
ensure_host_log_dir
print_deploy_context
update_latest_code

echo "开始构建并启动服务..."
run_compose up -d --build

echo
echo "部署完成。"
echo "查看状态: bash scripts/status.sh"
echo "查看日志: bash scripts/logs.sh"
