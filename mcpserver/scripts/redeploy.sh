#!/bin/bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

load_env_file
ensure_host_log_dir
print_deploy_context
update_latest_code

echo "重新构建并更新服务..."
run_compose up -d --build

echo
echo "更新完成。"
echo "查看最新日志: bash scripts/logs.sh"
