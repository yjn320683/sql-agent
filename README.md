# SQL Agent

SQL Agent 是数开平台 Claude Code Agent 项目，包含前端、后端、Agent、MCP、验数、SQL 解析、数据地图和实时任务模块。

## 模块

- `front`：React + Vite 页面，生产构建后由 backend 提供静态资源。
- `backend`：Spring Boot 网关，负责 SQL 任务、执行实例、会话、历史和 SSE 代理。
- `agent`：FastAPI + Claude Agent SDK，负责运行 Claude Code、SQL skill 和有界 Hive SQL 执行队列。
- `mcpserver`：HiveServer2、Metastore、WebHDFS、YARN、MapReduce JobHistory 和 Data Map 只读工具，由 agent 通过 stdio 拉起。
- `datacompare`：独立 Maven 验数模块，作为 backend 依赖随同一个进程部署，通过 HiveServer2 执行版本验数和 Hive 表对比。
- `parse-sql`：纯 Java/ANTLR Hive SQL 表级解析依赖，供 backend 和 datacompare 共用，不提供启动入口，不单独部署。
- `data-map`：数据地图和血缘相关能力。
- `realtime-backend`：实时任务 API、配置、运行观测和状态对账，作为 backend 依赖部署。
- `realtime-common`：实时任务提交协议与共享校验逻辑。
- `realtime-task-submit`：独立 Flink 任务提交 JAR。

实时同步任务参考 `/opt/project/gs/realtime-data-dev`，本次对齐版本 `0ffb1818`：

- 默认分区逐表配置，支持 NULL、空字符串和纯空白值兜底，按字段类型和计算列日期格式推荐与校验；默认分区需覆盖全部分区键，保留完整源物理主键及确定性计算列主键。
- 全量任务（`full_incremental`）首次全量后持续增量；增量任务可选最早、最新或指定时间戳消费点。已有正式实例时优先从 Savepoint / Checkpoint 恢复，直接重新消费增量需明确确认。
- 每个 Server 的两类任务独立计数与限额，默认各 1 个；`rt_sync_server_task_limit(source_server_id,sync_mode,max_task_count)` 可分别配置。旧任务保持相同 Server 和同步类型时不因超额阻断编辑。
- Bucket 可逐表设置 1～100；未设置时继承公共配置。
- 快照时间过滤逐表指定时间字段、开始时间和 MySQL 源端时区，仅允许全量任务，严格大于开始时间；Binlog 数据不受影响。
- 计算列支持常量 cast、按顺序引用前置计算列、日期格式预设与校验，以及整数/数字字符串时间戳精度 0/3/6/9。

逐表选项保存在原有配置 JSON 中。部署时执行 `bash scripts/db_migrate.sh migrate`，迁移 `V20261016_01__sync_server_type_limits.sql` 创建独立限额表。运行端 CDC 包必须支持 `--multiple_table_bucket`、`--multiple_table_partition_defaults` 和 `--multiple_table_time_filter`。本地已验证的 CDC 包 SHA-256：`28b7c4f6d40726237a789a0abca923ab405bb66c22059de13332034f4d8415e9`；升级包后，新提交任务使用新能力，正在运行的 Flink 作业保留原包。

实时任务主表的状态列按参考项目使用 `task_status`（上下线）、`instance_status`（运行态）和 `deleted_flag`（逻辑删除）。已部署旧表先执行 `realtime-backend/src/main/resources/db/upgrade-20261010-task-online-status.sql`，再执行 `realtime-backend/src/main/resources/db/upgrade-20261010-align-task-status.sql`；第二个脚本也支持尚未执行第一个脚本的旧表。新建库使用 `realtime_sync_schema.sql`。
已部署的实时库还需先执行 `realtime-backend/src/main/resources/db/upgrade-20261012-sync-param-editable.sql`，再执行 `realtime-backend/src/main/resources/db/upgrade-20261012-sync-ddl-exception.sql`，然后部署新版后端和前端。前者通过 `rt_task_param.editable_flag` 控制动态参数是否可编辑，后者创建 DDL 异常表；缺少后者时同步任务编辑页会因无法读取异常状态而禁用保存。两个脚本均可重复执行；新建库使用 `realtime_sync_schema.sql`，已包含对应结构。

DDL 异常上报需在实际运行环境配置 `SYNC_DDL_REPORT_URL`（Flink TaskManager 可访问的本后端 `/api/internal/sync/ddl-exceptions` 地址）与 `SYNC_DDL_REPORT_TOKEN`（后端和提交作业共用的非空密钥）。还需确认 Flink lib 中的 Action、定制 CDC、Paimon Flink 三个 JAR 与部署版本一致，填写 `SYNC_DDL_ACTION_SHA256`、`SYNC_DDL_CDC_JAR_PATH`、`SYNC_DDL_CDC_SHA256`、`SYNC_DDL_FLINK_JAR_PATH`、`SYNC_DDL_FLINK_SHA256`，验证后才将 `SYNC_DDL_REPORTER_COMPATIBLE` 设为 `true`。地址或 Token 为空时不会上报；两者仅配置其一或 JAR 校验失败时拒绝带上报参数启动。不要把真实 Token 写入仓库或 URL；提交配置文件按仅所有者可读写权限存放。

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
cd .. && mvn -q -pl backend,realtime-task-submit -am -DskipTests package
java -jar backend/target/sql-agent-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

手工重新打包前，先停止直接运行 `backend/target` JAR 的后端；Maven 会覆盖该文件。`scripts/deploy.sh` 会把后端切换到独立的运行副本，再执行后续构建。

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
