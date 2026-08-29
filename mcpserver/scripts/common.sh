#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOY_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly DEFAULT_APP_SUBDIR="code/mcp_server"
if [[ -f "${DEPLOY_ROOT}/${DEFAULT_APP_SUBDIR}/docker-compose.yml" ]]; then
  readonly APP_DIR="${DEPLOY_ROOT}/${DEFAULT_APP_SUBDIR}"
else
  readonly APP_DIR="${DEPLOY_ROOT}"
fi
if [[ -f "${DEPLOY_ROOT}/.env" ]]; then
  readonly ENV_FILE="${DEPLOY_ROOT}/.env"
else
  readonly ENV_FILE="${APP_DIR}/.env"
fi
readonly COMPOSE_FILE="${APP_DIR}/docker-compose.yml"

deploy_root() {
  printf '%s\n' "${DEPLOY_ROOT}"
}

app_dir() {
  printf '%s\n' "${APP_DIR}"
}

require_env_file() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "未找到 .env 文件：${ENV_FILE}"
    exit 1
  fi
}

require_compose_file() {
  if [[ ! -f "${COMPOSE_FILE}" ]]; then
    echo "未找到 docker-compose.yml：${COMPOSE_FILE}"
    exit 1
  fi
}

load_env_file() {
  require_env_file

  set -a
  # shellcheck disable=SC1091
  source "${ENV_FILE}"
  set +a
  export APP_ENV_FILE="${ENV_FILE}"
}

ensure_host_log_dir() {
  local host_log_dir
  host_log_dir="${HOST_LOG_DIR:-${DEPLOY_ROOT}/logs}"

  if [[ "${host_log_dir}" != /* ]]; then
    host_log_dir="${DEPLOY_ROOT}/${host_log_dir#./}"
  fi

  mkdir -p "${host_log_dir}"
  export HOST_LOG_DIR="${host_log_dir}"
}

print_deploy_context() {
  echo "部署根目录: ${DEPLOY_ROOT}"
  echo "代码目录: ${APP_DIR}"
  echo ".env 路径: ${ENV_FILE}"
  echo "日志目录: ${HOST_LOG_DIR:-${DEPLOY_ROOT}/logs}"
}

run_compose() {
  require_compose_file
  docker compose --project-directory "${APP_DIR}" -f "${COMPOSE_FILE}" "$@"
}

update_latest_code() {
  local branch before_commit after_commit remote_branch
  local git_check_output

  if ! git_check_output="$(cd "${APP_DIR}" && git rev-parse --is-inside-work-tree 2>&1)"; then
    echo "代码目录不是 Git 仓库，或当前 Git 状态异常：${APP_DIR}"
    echo "Git 原始错误：${git_check_output}"
    exit 1
  fi

  branch="$(cd "${APP_DIR}" && git rev-parse --abbrev-ref HEAD)"
  before_commit="$(cd "${APP_DIR}" && git rev-parse --short HEAD)"
  remote_branch="$(cd "${APP_DIR}" && git rev-parse --abbrev-ref --symbolic-full-name "@{u}" 2>/dev/null || true)"

  echo "开始同步最新代码..."
  echo "当前分支: ${branch}"
  echo "更新前提交: ${before_commit}"
  if [[ -n "${remote_branch}" ]]; then
    echo "跟踪分支: ${remote_branch}"
  else
    echo "跟踪分支: 未配置"
  fi

  (cd "${APP_DIR}" && git pull)

  after_commit="$(cd "${APP_DIR}" && git rev-parse --short HEAD)"
  echo "更新后提交: ${after_commit}"
  if [[ "${before_commit}" == "${after_commit}" ]]; then
    echo "代码已是最新，无新增提交。"
  else
    echo "代码已更新到最新提交。"
  fi
}
