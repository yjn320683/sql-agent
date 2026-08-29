# SQL Agent MCP Server 部署文档

本文档说明以下两种运行方式：

- 本地启动：`sql_agent_mcp_server.server`
- 生产部署：`Docker + Docker Compose`

如果你不想手动敲 `docker compose` 命令，正式环境推荐使用部署根目录下的总入口脚本：

- 部署根目录 `deploy.sh`

部署根目录 `deploy.sh` 的职责建议固定为：

- 先拉取代码仓库最新内容
- 同步 `${部署根目录}/scripts` 下的脚本
- 再调用 `bash scripts/redeploy.sh`

可直接在部署根目录创建如下脚本：

```bash
#!/bin/bash

set -euo pipefail

DEPLOY_ROOT="$(cd "$(dirname "$0")" && pwd)"
CODE_DIR="${DEPLOY_ROOT}/code/mcp_server"
SCRIPTS_DIR="${DEPLOY_ROOT}/scripts"

echo "部署根目录: ${DEPLOY_ROOT}"
echo "代码目录: ${CODE_DIR}"

cd "${CODE_DIR}"
git pull

mkdir -p "${SCRIPTS_DIR}"
cp -f scripts/*.sh "${SCRIPTS_DIR}/"
chmod +x "${SCRIPTS_DIR}"/*.sh

cd "${DEPLOY_ROOT}"
bash scripts/redeploy.sh
```

建议保存为：

```bash
${部署根目录}/deploy.sh
```

并赋予执行权限：

```bash
chmod +x deploy.sh
```

代码目录中的脚本仍作为二级脚本使用：

- 首次部署：`bash scripts/deploy.sh`
- 代码更新后重发：`bash scripts/redeploy.sh`
- 查看运行状态：`bash scripts/status.sh`
- 持续看日志：`bash scripts/logs.sh`
- 监控停止后自动拉起：`bash scripts/watch_and_restart.sh`
- 停止服务：`bash scripts/stop.sh`

说明：

- `deploy.sh` / `redeploy.sh` 执行时会先到代码目录拉取最新代码，再执行 `docker compose up -d --build`
- 如果代码目录不是 Git 工作区，或 Git 状态异常，脚本会打印原因并直接退出
- 仓库根目录直接运行的旧用法仍兼容，适合本地调试或临时部署

## 1. 服务说明

- 服务入口：`sql_agent_mcp_server.server:main`
- 传输方式：`Streamable HTTP`
- 默认端口：`8820`
- 默认路径：`/mcp`

默认访问地址：

```text
http://127.0.0.1:8820/mcp
```

## 2. 必需环境变量

启动前请准备 `.env` 文件，可参考代码目录中的 `.env.example`。

完整 Hive on MapReduce 诊断必需配置：

- `HIVE_METASTORE_URI`：Hive Metastore Thrift 地址
- `HIVE_SERVER2_URI`：PyHive binary transport 地址，格式 `hive://host:10000/database`
- `WEBHDFS_URLS`：NameNode WebHDFS HTTP 基础地址；HA 使用英文逗号分隔
- `YARN_RESOURCE_MANAGER_URLS`：ResourceManager HTTP 基础地址；HA 使用英文逗号分隔
- `MAPREDUCE_JOB_HISTORY_URLS`：MapReduce JobHistory HTTP 基础地址；多个地址使用英文逗号分隔

按需配置：

- `HIVE_METASTORE_DB_URI`：字段反查工具 `hive_find_column_usage` 需要的 Metastore 后端数据库连接串
- `DATA_MAP_DB_URI`：Data Map 数据源连接串
- `HADOOP_HTTP_USER`：Hadoop simple 模式可选 `user.name`，无需时留空

通用运行配置：

- `MCP_TOOL_TIMEOUT_SECONDS`：单个 tool 超时时间
- `MCP_LOG_LEVEL`：日志级别
- `MCP_LOG_FILE`：日志文件路径，默认 `logs/sql_agent_mcp_server.log`
- `MCP_LOG_BACKUP_COUNT`：按天轮转日志的保留份数，默认 `7`
- `MCP_HTTP_HOST`：监听地址；本地开发通常使用 `127.0.0.1`，正式环境建议写为 `0.0.0.0`
- `MCP_HTTP_PORT`：服务端口，默认 `8820`
- `MCP_HTTP_PATH`：MCP HTTP 路径，默认 `/mcp`

仅部署编排内部使用的兼容变量：

- `APP_ENV_FILE`：供 `docker-compose.yml` 引用实际 `.env` 路径，通常由脚本自动导出
- `SQL_AGENT_MCP_PROJECT_ROOT` / `APP_HOME`：运行时显式项目根目录；通常不需要手工设置

仅 Docker 编排使用的宿主机可选变量：

- `HOST_LOG_DIR`：宿主机日志目录，默认 `${部署根目录}/logs`

## 3. 本地启动

### 3.1 环境要求

- Python：`3.12.x`

### 3.2 安装依赖

在项目根目录执行：

```bash
pip install -e ".[dev]"
```

### 3.3 准备配置

复制环境变量模板并填写真实值：

```bash
cp .env.example .env
```

本地开发默认可保留：

```env
MCP_HTTP_HOST=127.0.0.1
MCP_HTTP_PORT=8820
MCP_HTTP_PATH=/mcp
```

生产架构中这些连接参数以 `agent/.env` 为权威来源，由 agent 原样注入 stdio mcpserver；不要在根目录新增 `.env`，也不要把真实地址或凭据提交到仓库。

### 3.4 启动命令

```bash
python -m sql_agent_mcp_server.server
```

如果未做 editable install，也可以使用：

```bash
PYTHONPATH=src python -m sql_agent_mcp_server.server
```

### 3.5 本地验证

启动后确认服务监听在：

```text
http://127.0.0.1:8820/mcp
```

可通过以下命令检查端口与路径是否可达：

```bash
curl -i http://127.0.0.1:8820/mcp
```

## 4. 生产部署

### 4.0 推荐目录结构

正式环境推荐按以下目录组织：

```text
部署根目录/
├── .env
├── logs/
├── scripts/
└── code/
    └── mcp_server/
```

说明：

- `.env` 放在部署根目录，不放在代码目录
- `logs` 是宿主机日志映射目录
- `scripts` 在部署根目录执行
- 代码目录固定为 `code/mcp_server`
- 部署脚本会自动推导代码目录，不需要额外新增路径环境变量

### 4.1 部署前准备

生产部署前请确认：

- 已准备好部署根目录下的 `.env` 文件
- `.env` 中的 `HIVE_METASTORE_URI` 已填写真实值
- 如果需要字段反查，`HIVE_METASTORE_DB_URI` 已填写真实值
- 如果需要 Data Map，`DATA_MAP_DB_URI` 已填写真实值
- 宿主机已安装 Docker 和 Docker Compose
- 宿主机已开放对外访问的服务端口
- 部署机可以访问 Hive Metastore 和对应后端数据库

生产推荐将 `.env` 中的监听地址写为：

```env
MCP_HTTP_HOST=0.0.0.0
```

当前 `docker-compose.yml` 也会在容器内强制使用 `0.0.0.0` 监听，避免容器只绑定到回环地址。

### 4.2 构建镜像

如果使用正式环境的总入口脚本，可直接在部署根目录执行：

```bash
bash deploy.sh
```

如果需要直接调用代码目录同步下来的二级脚本，也可以在部署根目录执行：

```bash
bash scripts/deploy.sh
```

如需手动构建镜像，请先进入代码目录：

```bash
cd code/mcp_server
docker build -t sql-agent-mcp-server:latest .
```

### 4.3 启动容器

当前仓库已包含 `Dockerfile` 和 `docker-compose.yml`。更推荐直接使用 `docker compose`，因为当前 compose 已内置：

- `APP_ENV_FILE`
- `HOST_LOG_DIR`
- `MCP_HTTP_PORT`

在正式环境中，推荐在部署根目录执行：

```bash
bash deploy.sh
```

该总入口脚本建议流程为：

1. 先拉取最新代码
2. 同步 `${部署根目录}/scripts`
3. 调用 `bash scripts/redeploy.sh`

如果需要跳过总入口脚本，直接调用二级部署脚本，则在部署根目录执行：

```bash
bash scripts/deploy.sh
```

代码更新后重发：

```bash
bash scripts/redeploy.sh
```

脚本执行过程中会输出：

- 部署根目录
- 代码目录
- `.env` 路径
- 日志目录
- 当前分支
- 更新前后提交号
- `docker compose` 启动结果

如果你仍然采用旧的单层部署方式，也可以直接在仓库根目录执行上述二级脚本，行为保持兼容。

### 4.4 端口与日志约定

默认情况下：

- 容器镜像名：`sql-agent-mcp-server:latest`
- 容器名：`sql-agent-mcp-server`
- 宿主机端口：`${MCP_HTTP_PORT:-8820}`
- 容器内端口：`${MCP_HTTP_PORT:-8820}`
- 日志目录挂载：`${HOST_LOG_DIR:-./logs}:/app/logs`

如果你把 `MCP_LOG_FILE` 改成非 `logs/` 目录下的路径，需要同步调整 `docker-compose.yml` 中的日志卷挂载，否则容器内文件不会落到宿主机预期目录。

### 4.5 发布后验证

1. 确认容器状态正常：

```bash
bash scripts/status.sh
```

2. 查看服务日志：

```bash
bash scripts/logs.sh
```

3. 使用本机地址验证 MCP 路径可达：

```bash
curl -i "http://127.0.0.1:${MCP_HTTP_PORT:-8820}${MCP_HTTP_PATH:-/mcp}"
```

4. 如需通过域名或代理验证，请将地址替换为实际访问入口：

```bash
curl -i "http://你的域名${MCP_HTTP_PATH:-/mcp}"
```

## 5. 常见排障

### 5.1 容器启动后端口不通

优先检查：

- `.env` 是否存在
- `bash scripts/status.sh` 输出的服务是否为 `Up`
- `MCP_HTTP_PORT` 是否被其他进程占用
- 宿主机防火墙或安全组是否已放通目标端口

### 5.2 容器能启动但访问 Hive / Data Map 失败

优先检查：

- `HIVE_METASTORE_URI` 是否正确
- `HIVE_METASTORE_DB_URI` 是否正确且数据库网络可达
- `DATA_MAP_DB_URI` 是否正确且数据库网络可达
- 部署机到目标服务的网络是否可达

### 5.3 宿主机看不到日志文件

优先检查：

- `HOST_LOG_DIR` 是否配置正确
- `MCP_LOG_FILE` 是否仍位于容器内的 `logs/` 目录
- 宿主机挂载目录是否有写权限
- 当前是否是在部署根目录执行二级脚本，避免相对路径落到错误位置
