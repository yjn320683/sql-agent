#!/bin/bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOY_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
if [[ -f "${DEPLOY_ROOT}/code/sql-agent/docker-compose.yml" ]]; then
  readonly APP_DIR="${DEPLOY_ROOT}/code/sql-agent"
else
  readonly APP_DIR="${DEPLOY_ROOT}"
fi
if [[ -f "${DEPLOY_ROOT}/agent/.env" ]]; then
  readonly ENV_FILE="${DEPLOY_ROOT}/agent/.env"
else
  readonly ENV_FILE="${APP_DIR}/agent/.env"
fi
readonly COMPOSE_FILE="${APP_DIR}/docker-compose.yml"
readonly FRONT_DIR="${APP_DIR}/front"
readonly BACKEND_DIR="${APP_DIR}/backend"
readonly BACKEND_PID_FILE="${DEPLOY_ROOT}/run/sql-agent-backend.pid"

require_env_file() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "未找到 .env 文件：${ENV_FILE}"
    echo "请复制 agent/.env.example 为 agent/.env 后填写运行配置，或执行 scripts/generate_env_from_references.py --force 生成。"
    exit 1
  fi
}

load_env_file() {
  require_env_file
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
  export APP_ENV_FILE="${ENV_FILE}"
}

ensure_host_dirs() {
  local host_log_dir host_claude_dir
  host_log_dir="${HOST_LOG_DIR:-${DEPLOY_ROOT}/logs}"
  host_claude_dir="${HOST_CLAUDE_DIR:-${DEPLOY_ROOT}/.claude}"
  [[ "${host_log_dir}" == /* ]] || host_log_dir="${DEPLOY_ROOT}/${host_log_dir#./}"
  [[ "${host_claude_dir}" == /* ]] || host_claude_dir="${DEPLOY_ROOT}/${host_claude_dir#./}"
  mkdir -p "${host_log_dir}/task-executions" "${host_log_dir}/data-compare" "${host_claude_dir}" "${DEPLOY_ROOT}/run"
  touch "${host_log_dir}/claude-debug.log" "${host_log_dir}/sql-agent-mcp-server.log" \
    "${host_log_dir}/sql-agent-backend.log"
  export HOST_LOG_DIR="${host_log_dir}"
  export HOST_CLAUDE_DIR="${host_claude_dir}"
}

print_deploy_context() {
  echo "部署根目录: ${DEPLOY_ROOT}"
  echo "代码目录: ${APP_DIR}"
  echo ".env 路径: ${ENV_FILE}"
  echo "Agent 端口: ${AGENT_PORT:-8284}"
  echo "日志目录: ${HOST_LOG_DIR:-${DEPLOY_ROOT}/logs}"
  echo "Claude 目录: ${HOST_CLAUDE_DIR:-${DEPLOY_ROOT}/.claude}"
  echo "Backend PID: ${BACKEND_PID_FILE}"
  echo "验数模块: 随 Backend 进程部署"
}

run_compose() {
  docker compose --project-directory "${APP_DIR}" -f "${COMPOSE_FILE}" "$@"
}

update_latest_code() {
  if [[ "${SKIP_GIT_PULL:-false}" == "true" ]]; then
    echo "已设置 SKIP_GIT_PULL=true，跳过 git pull。"
    return
  fi
  if ! (cd "${APP_DIR}" && git rev-parse --is-inside-work-tree >/dev/null 2>&1); then
    echo "代码目录不是 Git 仓库：${APP_DIR}"
    exit 1
  fi
  local before_commit after_commit branch
  branch="$(cd "${APP_DIR}" && git rev-parse --abbrev-ref HEAD)"
  before_commit="$(cd "${APP_DIR}" && git rev-parse --short HEAD)"
  echo "当前分支: ${branch}"
  echo "更新前提交: ${before_commit}"
  (cd "${APP_DIR}" && git pull --ff-only)
  after_commit="$(cd "${APP_DIR}" && git rev-parse --short HEAD)"
  echo "更新后提交: ${after_commit}"
}

backend_config_dir() {
  if [[ -f "${DEPLOY_ROOT}/backend/config/application-local.yml" ]]; then
    printf '%s\n' "${DEPLOY_ROOT}/backend/config"
    return
  fi
  if [[ -f "${BACKEND_DIR}/config/application-local.yml" ]]; then
    printf '%s\n' "${BACKEND_DIR}/config"
  fi
}

backend_log_file() {
  printf '%s\n' "${HOST_LOG_DIR:-${DEPLOY_ROOT}/logs}/sql-agent-backend.log"
}

backend_jar() {
  printf '%s\n' "${BACKEND_DIR}/target/sql-agent-backend-0.0.1-SNAPSHOT.jar"
}

backend_pid() {
  if [[ -f "${BACKEND_PID_FILE}" ]]; then
    tr -d '[:space:]' <"${BACKEND_PID_FILE}"
  fi
}

is_backend_running() {
  local pid command_line
  pid="$(backend_pid)"
  if [[ ! "${pid}" =~ ^[0-9]+$ ]] || ! kill -0 "${pid}" 2>/dev/null; then
    return 1
  fi
  command_line="$(ps -p "${pid}" -o args= 2>/dev/null || true)"
  [[ "${command_line}" == *"$(backend_jar)"* ]]
}

build_application() {
  echo "构建前端静态资源..."
  (cd "${FRONT_DIR}" && npm ci --no-audit --no-fund && npm run build)
  echo "打包 backend（包含 datacompare、parse-sql 模块）..."
  (cd "${APP_DIR}" && mvn -q -pl backend -am -DskipTests package)
}

wait_agent_ready() {
  local port="${AGENT_PORT:-8284}"
  wait_http_ready "Agent" "http://127.0.0.1:${port}/health" 120
}

wait_backend_ready() {
  wait_http_ready "Backend" "${BACKEND_READY_URL:-http://127.0.0.1:8382/api/ready}" 90
}

wait_http_ready() {
  local name="$1" url="$2" timeout_seconds="$3"
  local elapsed=0
  while (( elapsed < timeout_seconds )); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "${name} 已就绪: ${url}"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "${name} 在 ${timeout_seconds}s 内未就绪: ${url}"
  return 1
}

start_backend() {
  local config_dir jar_file log_file java_bin pid
  local -a java_opts=() command
  config_dir="$(backend_config_dir)"
  jar_file="$(backend_jar)"
  log_file="$(backend_log_file)"
  java_bin="${JAVA_BIN:-java}"
  if [[ -n "${BACKEND_JAVA_OPTS:-}" ]]; then
    read -r -a java_opts <<<"${BACKEND_JAVA_OPTS}"
  fi

  if [[ -z "${config_dir}" ]]; then
    echo "未找到外置 backend 配置：${BACKEND_DIR}/config/application-local.yml"
    echo "请复制 ${BACKEND_DIR}/config/application-local.yml.example 后填写真实配置。"
    exit 1
  fi
  if [[ ! -f "${jar_file}" ]]; then
    echo "未找到 backend jar: ${jar_file}"
    exit 1
  fi
  if is_backend_running; then
    echo "Backend 已运行，PID=$(backend_pid)"
    return
  fi

  echo "启动 backend..."
  command=("${java_bin}")
  if [[ -n "${BACKEND_JAVA_OPTS:-}" ]]; then
    command+=("${java_opts[@]}")
  fi
  command+=(
    -jar "${jar_file}"
    --spring.profiles.active=local
  )
  if [[ -n "${config_dir}" ]]; then
    command+=(--spring.config.additional-location="file:${config_dir}/")
  fi
  command+=(--app.task-execution-log.dir="${HOST_LOG_DIR}/task-executions")
  (
    cd "${BACKEND_DIR}"
    nohup "${command[@]}" >>"${log_file}" 2>&1 &
    echo $! >"${BACKEND_PID_FILE}"
  )
  pid="$(backend_pid)"
  echo "Backend PID=${pid}，日志=${log_file}"
  if ! wait_backend_ready; then
    tail -n 120 "${log_file}" || true
    stop_backend
    return 1
  fi
}

stop_backend() {
  local pid elapsed=0
  if ! is_backend_running; then
    rm -f "${BACKEND_PID_FILE}"
    echo "Backend 未运行。"
    return
  fi
  pid="$(backend_pid)"
  echo "停止 backend，PID=${pid}..."
  kill "${pid}"
  while kill -0 "${pid}" 2>/dev/null && (( elapsed < 30 )); do
    sleep 1
    elapsed=$((elapsed + 1))
  done
  if kill -0 "${pid}" 2>/dev/null; then
    echo "Backend 未在 30s 内退出，强制结束 PID=${pid}。"
    kill -9 "${pid}"
  fi
  rm -f "${BACKEND_PID_FILE}"
}

restart_backend() {
  stop_backend
  start_backend
}

print_backend_status() {
  if is_backend_running; then
    echo "Backend: running (PID=$(backend_pid))"
  else
    echo "Backend: stopped"
  fi
}
