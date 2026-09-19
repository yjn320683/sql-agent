#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ACTION="${1:-validate}"

case "${ACTION}" in
  info|validate|migrate) ;;
  *) echo "用法: $0 {info|validate|migrate}"; exit 2 ;;
esac

CONFIG_FILE="${SQL_AGENT_DB_CONFIG:-${APP_DIR}/backend/config/application-local.yml}"
if [[ ! -f "${CONFIG_FILE}" ]]; then
  CONFIG_FILE="${APP_DIR}/backend/src/main/resources/application-local.yml"
fi

yaml_datasource_value() {
  local key="$1"
  awk -v wanted="${key}:" '
    /^[[:space:]]+datasource:[[:space:]]*$/ { inside=1; next }
    inside && /^[^[:space:]]/ { exit }
    inside {
      field=$1
      if (field == wanted) {
        sub(/^[[:space:]]*[^:]+:[[:space:]]*/, "")
        gsub(/^"|"$/, "")
        print
        exit
      }
    }
  ' "${CONFIG_FILE}"
}

DB_URL="${SQL_AGENT_DB_URL:-$(yaml_datasource_value url)}"
DB_USER="${SQL_AGENT_DB_USER:-$(yaml_datasource_value username)}"
DB_PASSWORD="${SQL_AGENT_DB_PASSWORD:-$(yaml_datasource_value password)}"
if [[ -z "${DB_URL}" || -z "${DB_USER}" ]]; then
  echo "未找到数据库连接。请配置 SQL_AGENT_DB_URL/USER/PASSWORD 或 SQL_AGENT_DB_CONFIG。"
  exit 1
fi

if [[ ! "${DB_URL}" =~ ^jdbc:mysql://([^:/?]+)(:([0-9]+))?/([^?]+) ]]; then
  echo "当前迁移脚本只支持 jdbc:mysql URL。"
  exit 1
fi
DB_HOST="${BASH_REMATCH[1]}"
DB_PORT="${BASH_REMATCH[3]:-3306}"
DB_NAME="${BASH_REMATCH[4]}"

mysql_scalar() {
  MYSQL_PWD="${DB_PASSWORD}" mysql --batch --skip-column-names --host="${DB_HOST}" --port="${DB_PORT}" \
    --user="${DB_USER}" "${DB_NAME}" --execute="$1"
}

history_exists() {
  local count
  if ! count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'")"; then
    echo "无法连接数据库并检查 Flyway 迁移历史。" >&2
    return 2
  fi
  [[ "${count}" == "1" ]]
}

preflight_existing_schema() {
  local count
  count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('sql_task','rt_task','task_lineage_snapshot','data_map_graph_outbox','data_map_projection_generation','rt_realtime_table')")"
  if [[ "${count}" != "6" ]]; then
    echo "现有库未通过基线检查：核心表应为 6 个，实际 ${count} 个。拒绝建立虚假基线。"
    exit 1
  fi
  count="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND (table_name,column_name) IN (('data_map_graph_outbox','status'),('data_map_graph_outbox','locked_at'),('data_map_graph_outbox','available_at'),('task_lineage_snapshot','lineage_json'))")"
  if [[ "${count}" != "4" ]]; then
    echo "现有库未通过基线检查：数据地图关键字段不完整。"
    exit 1
  fi
}

FLYWAY_CONF="$(mktemp "${TMPDIR:-/tmp}/sql-agent-flyway.XXXXXX.conf")"
trap 'rm -f "${FLYWAY_CONF}"' EXIT
chmod 600 "${FLYWAY_CONF}"
{
  printf 'flyway.url=%s\n' "${DB_URL}"
  printf 'flyway.user=%s\n' "${DB_USER}"
  printf 'flyway.password=%s\n' "${DB_PASSWORD}"
  printf 'flyway.locations=filesystem:%s/backend/src/main/resources/db/migration\n' "${APP_DIR}"
  printf 'flyway.baselineVersion=20260917\n'
  printf 'flyway.baselineDescription=Existing schema through batch 9\n'
  printf 'flyway.validateMigrationNaming=true\n'
} >"${FLYWAY_CONF}"

flyway_goal() {
  (cd "${APP_DIR}" && mvn -q -f scripts/flyway-pom.xml org.flywaydb:flyway-maven-plugin:9.22.3:"$1" \
    -Dflyway.configFiles="${FLYWAY_CONF}")
}

HAS_HISTORY=false
if history_exists; then
  HAS_HISTORY=true
else
  HISTORY_STATUS=$?
  if [[ "${HISTORY_STATUS}" == "2" ]]; then
    exit 1
  fi
fi

if [[ "${ACTION}" == "validate" && "${HAS_HISTORY}" == "false" ]]; then
  echo "数据库尚未建立迁移基线。默认校验不会执行 DDL；请显式运行 $0 migrate。"
  exit 1
fi

if [[ "${ACTION}" == "migrate" ]]; then
  if [[ "${HAS_HISTORY}" == "false" ]]; then
    preflight_existing_schema
    echo "现有结构检查通过，建立 Flyway 基线。"
    flyway_goal baseline
  fi
  flyway_goal migrate
  flyway_goal validate
  echo "数据库迁移及校验完成。"
else
  flyway_goal "${ACTION}"
fi
