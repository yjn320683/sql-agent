# SQL Agent

SQL Agent 是数开平台 Claude Code Agent 项目，包含 `front`、`backend`、`agent`、`mcpserver`、`datacompare` 和 `parse-sql` 六个模块。

## 模块

- `front`：React + Vite 页面，生产构建后由 backend 提供静态资源。
- `backend`：Spring Boot 网关，负责 SQL 任务、执行实例、会话、历史和 SSE 代理。
- `agent`：FastAPI + Claude Agent SDK，负责运行 Claude Code、SQL skill 和有界 Hive SQL 执行队列。
- `mcpserver`：HiveServer2、Metastore、WebHDFS、YARN、MapReduce JobHistory 和 Data Map 只读工具，由 agent 通过 stdio 拉起。
- `datacompare`：独立 Maven 验数模块，作为 backend 依赖随同一个进程部署，通过 HiveServer2 执行版本验数和 Hive 表对比。
- `parse-sql`：纯 Java/ANTLR Hive SQL 表级解析依赖，供 backend 和 datacompare 共用，不提供启动入口，不单独部署。

## 本地验证

首次运行先复制各模块配置模板，并填写当前环境的真实连接信息：

```bash
cp agent/.env.example agent/.env
cp mcpserver/.env.example mcpserver/.env
cp front/.env.example front/.env
cp backend/config/application-local.yml.example backend/config/application-local.yml
```

所有真实配置均被 Git 忽略；`.example` 中只包含 `127.0.0.1`、`example_*` 和项目相对路径等安全样例。

首次准备 Python 联调环境：

```bash
cd agent
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m pip install -e '../mcpserver[dev]'
```

```bash
cd front && npm run build
cd .. && mvn -q -pl backend -am test
mvn -q -pl datacompare test
cd ../agent && .venv/bin/python -m pytest -q
cd ../mcpserver && ../agent/.venv/bin/python -m pytest -q
```

Agent 本地命令行启动（开发模式，端口 `8285`）：

```bash
cd /opt/project/ai-project/sql-agent/agent && ./.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8285 --reload
```

## 生产部署

需要自动生成本地样例配置时执行：

```bash
python3 scripts/generate_env_from_references.py --force
```

脚本仅使用安全样例值生成 `agent/.env` 和 `backend/config/application-local.yml`。生成后必须填写当前环境的真实连接信息；真实连接串、Token 和密码不会写入仓库。

如需调整环境：

- backend 数据库、Agent 和实时组件地址：修改外置 `backend/config/application-local.yml`，模板见同目录 `.example`
- agent 的 Claude、业务库、Hive/HDFS/YARN/JobHistory/Data Map、MCP 配置：修改 `agent/.env`，模板见 `agent/.env.example`
- MCP Server 独立启动时使用 `mcpserver/.env`，前端代理地址使用 `front/.env`

`backend/src/main/resources/application.yml` 仅保留无敏感信息的通用默认项；Local/Dev 环境必须从 `backend/config` 外置加载。

## SQL 任务、版本与 Step 表

任务工作台需要 `sql_task`、`sql_task_version`、`sql_task_version_step`、`sql_task_execution` 和 `sql_task_execution_step`。项目不会在启动时自动建表，也没有配置 `spring.sql.init` 或 ORM DDL；首次部署由管理员显式执行完整脚本：

```text
backend/src/main/resources/db/sql_task.sql
```

已部署旧表时，显式执行手动版本迁移：

```text
backend/src/main/resources/db/20260825_task_workbench.sql
```

任务类型、执行频率、负责人和任务状态字段使用独立迁移：

```text
backend/src/main/resources/db/20260826_task_metadata.sql
```

执行示例（请先备份并由数据库管理员确认目标库）：

```bash
mysql -h <host> -P <port> -u <user> -p <database> < backend/src/main/resources/db/sql_task.sql
mysql -h <host> -P <port> -u <user> -p <database> < datacompare/src/main/resources/db/data_compare.sql
```

从旧版本模型升级时，显式执行：

```text
backend/src/main/resources/db/20260826_version_draft_model.sql
```

`sql_task`保存当前生效代码；每条`sql_task_version`记录是一份可编辑版本。新版本始终复制最新生效代码，同一版本可反复保存，生效后才更新任务代码；旧基线版本自动转为历史或过期只读。执行实例会记录生效代码revision或指定版本号、参数、渲染SQL与Step快照。

## 数据验数

验数能力使用独立代码模块和独立业务表，但不启动独立服务；它由 backend 直接调用并随 backend 一起部署。首次部署由管理员显式执行：

```text
datacompare/src/main/resources/db/data_compare.sql
```

已有环境只执行增量迁移：

```text
backend/src/main/resources/db/20260827_data_compare_workflow.sql
```

验数模块复用 backend 的 MySQL 数据源，Hive JDBC、专用 Hive 验数库和日志目录统一配置在 backend 的 `data-compare` 节点中。部署机使用外置 `backend/config/application-local.yml`。专用验数库只需允许 `CREATE/INSERT/SELECT`；系统不会生成 `DROP`，调测表和差异表采用计划令牌生成唯一名称并保留。生产 Hive DDL 仅允许联合发布链路执行 `ALTER TABLE ADD COLUMNS` 或 `CHANGE COLUMN`。

`agent/.env` 中的 `SQL_AGENT_DB_URI` 必须指向 backend 使用的同一业务库。执行实例日志默认写入共享目录 `logs/task-executions/{executionId}.log`；backend 的 `app.task-execution-log.dir` 与 agent 容器挂载目录必须指向同一位置。

任务脚本支持 `====step:n====`，无标记脚本视为 `step:0`。每个 Step 仅接受一条 `SELECT`、`WITH` 或 `INSERT` Hive SQL，拒绝 DDL、多语句和管理命令。Agent 聊天请求只携带 `taskId` 和可选 `executionId/versionNo`，SQL 由只读 MCP 工具查询，前端不再传 SQL 文本。

## 健康检查与故障排查

`/api/workspace/platform/health` 由 backend 聚合 Agent，以及 Agent 返回的 HiveServer2、Metastore、WebHDFS、YARN ResourceManager 和 MapReduce JobHistory 检查。Datacompare 是 backend 内部模块，不再作为独立服务检查。单个服务失败只标记该项，不会清空其他真实结果；页面自动刷新失败不会重复弹出相同 Toast。

排查顺序：

1. `bash scripts/status.sh` 查看 Agent 与 Backend 进程状态。
2. `bash scripts/logs.sh agent` 查看 Agent 与 MCP stdio 启动错误。
3. `bash scripts/logs.sh backend` 查看数据库、代理与静态资源错误。
4. `bash scripts/logs.sh datacompare` 查看 Backend 中的 Hive 验数连接和执行错误。
5. 确认 `agent/.env` 中的 Hadoop 地址与 `SQL_AGENT_DB_URI`，以及 backend 的外置 `application-local.yml`。

一键部署会并行构建 agent Docker 镜像与 front/backend，并依次完成 agent 健康检查、backend 宿主机进程重启和数据库就绪检查：

```bash
bash scripts/deploy.sh
```

其中 backend 不是容器部署；front 的 `dist` 会打入 backend jar，mcpserver 由 agent 内 Claude Code 进程通过 stdio 按需拉起。也可以手工启动：

```bash
cd front && npm run build
cd .. && mvn -q -pl backend -am -DskipTests package
java -jar backend/target/sql-agent-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

`front` 不单独运行，构建后的 dist 由 backend 提供静态资源。`mcpserver` 由 agent 内 Claude Code 进程通过 stdio 启动。

运维命令：

```bash
bash scripts/status.sh
bash scripts/logs.sh backend
bash scripts/logs.sh agent
bash scripts/logs.sh datacompare
python3 scripts/smoke_test.py --ob-id <测试用户obId>
python3 scripts/smoke_test.py --ob-id <测试用户obId> --live-agent
bash scripts/stop.sh
```

## Verified Commands

```bash
cd front && npm run build
cd .. && mvn -q -pl backend -am test
mvn -q -pl datacompare test
cd ../agent && .venv/bin/python -m pytest -q
cd ../mcpserver && ../agent/.venv/bin/python -m pytest -q
bash -n scripts/common.sh scripts/deploy.sh scripts/redeploy.sh scripts/status.sh scripts/logs.sh scripts/stop.sh scripts/watch_and_restart.sh
```
