# SQL Agent MCP 接口

本文件是 Agent 可调用工具的唯一清单。所有工具只读；只有 `ok=true` 的结果可作为事实。`complete=false` 时必须同时阅读 `missingReasons` 和 `warnings`，不得把采样或已过期历史描述成完整事实。

## 已实现工具

### 数据开发页面上下文与 Proposal

| 工具 | 用途 | 关键入参 |
| --- | --- | --- |
| `platform_context_get` | 查询调度、验数、实时任务/实例/表/Server/告警或平台状态的脱敏事实；实时实例会尽力补充 Flink 概览、资源与 Checkpoint | `contextType`, `entityId` |
| `platform_proposal_present` | 展示不执行的 SQL、DDL 或字段补丁 Proposal | `target`, `kind`, `before`, `after`, `patch`, `baseRevision`, `summary`, `risks` |

`platform_context_get` 不返回 Server 密码、Token、完整连接串、脏数据原始载荷或内部调用栈。Proposal 只允许前端确认写入本地草稿，不代表保存或执行成功。

### SQL 任务与执行实例

| 工具 | 用途 | 关键入参 |
| --- | --- | --- |
| `sql_task_get` | 按任务 ID 查询任务名称、描述和当前 Hive SQL | `taskId` |
| `sql_task_execution_get` | 查询一个实例的状态、运行标识、错误摘要和有限脱敏日志尾部 | `executionId` |
| `sql_task_execution_list` | 查询任务最近执行实例 | `taskId`, `limit` |

仅 SQL 专用命令和离线任务/版本页面必须先调用 `sql_task_get`。指定了 `executionId` 时再调用 `sql_task_execution_get`；未指定但需要实际运行事实时先调用 `sql_task_execution_list`。其它页面按 `platform-assist.md` 的 contextType 路由，不能强行调用离线任务工具。

### 平台与 Hive

| 工具 | 用途 | 关键入参 |
| --- | --- | --- |
| `platform_dependency_health_get` | 检查 HS2、Metastore、WebHDFS、RM、JobHistory 连通性 | 无 |
| `hive_task_sql_validate` | 在 MCP 服务端读取任务当前 SQL，并用目标 Hive 的只读 `EXPLAIN` 做编译校验 | `taskId`, `defaultDb` |
| `hive_task_sql_explain` | 在 MCP 服务端读取任务当前 SQL并获取预测计划，不经模型复制 SQL | `taskId`, `defaultDb`, `extended` |
| `hive_sql_validate` | 校验模型生成的候选 SQL，不用于校验原任务 SQL | `sql`, `defaultDb` |
| `hive_sql_explain` | 获取候选 SQL 的预测计划，不用于解释原任务 SQL | `sql`, `defaultDb`, `extended` |
| `hive_function_search` | 通过目标 HiveServer2 的 `SHOW FUNCTIONS` 查询实际已注册函数 | `keyword`, `limit`, `offset`, `defaultDb` |
| `hive_function_get` | 查询目标 HiveServer2 返回的函数签名、实现类和扩展说明 | `name`, `defaultDb` |
| `hive_list_databases` | 查询库列表 | `catalog` |
| `hive_search_tables` | 按库和表名子串搜索 | `catalog`, `pattern`, `db`, `limit`, `offset` |
| `hive_get_table` | 查询表属性 | `catalog`, `db`, `table`, `includeColumns`, `includePartitions` |
| `hive_get_columns` | 查询字段、类型和分区字段 | `catalog`, `db`, `table` |
| `hive_get_partitions` | 分页查询分区 | `catalog`, `db`, `table`, `limit`, `offset` |
| `hive_get_table_ddl` | 查询 Metastore 中可还原的 DDL | `catalog`, `db`, `table` |
| `hive_table_statistics_get` | 查询表、指定分区和指定字段统计 | `catalog`, `db`, `table`, `partitions`, `columns` |
| `hive_storage_layout_get` | 查询表或指定分区的 HDFS 大小、文件数和文件分布 | `catalog`, `db`, `table`, `partitions`, `maxFiles` |
| `hive_table_freshness_get` | 查询有界分区样本和 HDFS 路径修改时间，不判定业务 SLA | `catalog`, `db`, `table`, `partitionScanLimit`, `pathSampleLimit` |
| `hive_find_column_usage` | 按字段名反查表 | `catalog`, `column`, `db`, `tablePattern`, `limit`, `offset` |
| `data_map_get_table_primary_keys` | 查询 Data Map 登记的主键 | `db`, `table` |

### Hive on MapReduce 运行事实

| 工具 | 用途 | 关键入参 |
| --- | --- | --- |
| `mapreduce_job_search` | 用精确 ID 或有界条件定位已完成 Job | `jobId`, `applicationId`, `queryId`, `user`, `queue`, `name`, 时间范围, `state`, `limit` |
| `mapreduce_job_diagnostics_get` | 聚合一个 Hive 查询对应的多个 MR Job、Task、Counter 和异常 Attempt | `jobIds`, `applicationIds`, `includeTaskOutliers`, `outlierLimit` |
| `mapreduce_aggregated_logs_get` | 从 JobHistory 读取有限、脱敏的 MR ApplicationMaster 聚合日志和异常摘要 | `jobIds`, `logTypes`, `tailLines`, `maxCharsPerLog` |
| `yarn_application_diagnostics_get` | 补充 Application、attempt、container、排队和资源信息 | `applicationIds`, `includeAttempts`, `includeContainers` |
| `mapreduce_job_compare` | 比较两组可比 MR Job | `baselineJobIds`, `candidateJobIds` |

## 调用顺序

- `/sql优化`：运行标识明确时先 `mapreduce_job_diagnostics_get`；标识不明确先 `mapreduce_job_search`。只有失败、重试或诊断不足时才调用 `mapreduce_aggregated_logs_get`。原任务预测计划必须调用 `hive_task_sql_explain(taskId)`，再按热路径补统计、HDFS 布局和必要元数据。
- `/sql修复`：原任务语法/语义校验必须调用 `hive_task_sql_validate(taskId)`；运行失败先定位 Job、读取 JobHistory 诊断，再按需读取聚合日志；字段、分区或写入结构问题只取直接相关元数据。
- `/sql解释`：逻辑解释只读 SQL；原任务预测执行方式调用 `hive_task_sql_explain(taskId)`；真实执行说明必须绑定已确认的 Job/Application。
- `/sql生成`：先确认表字段和业务契约；需要目标引擎编译验证时调用 `hive_sql_validate`。编译通过不代表数据或结果正确。
- `/sql静态检查`：先通过 `sql_task_get` 获取任务 SQL，再按静态检查 reference 工作；不得要求用户重复粘贴 SQL。

## 运行事实约束

- 仅支持 Hive on MapReduce。一条 Hive SQL 可能产生多个 MR Job，不能只分析第一个 Job。
- `applicationId` 与 `jobId` 可以在 MapReduce 场景转换；`queryId` 搜索必须提供窄时间范围，候选不唯一时调用 `AskUserQuestion`。
- JobHistory 是已完成任务的主事实源；ResourceManager 历史可能过期，缺失时不得丢弃仍有效的 JobHistory 结论。
- JobHistory 配置只返回白名单参数和 SQL/输入路径指纹，不返回 SQL 原文、完整 `job.xml`、连接串或凭据。
- Task 分布和 Counter 已由服务端聚合；不要要求完整 Task、Container 或日志流。
- 聚合日志复用 `MAPREDUCE_JOB_HISTORY_URLS` 的 `19888`，只返回最多 5 个 Job 的 AM Container 有界日志；`complete=false` 可能表示日志未聚合、已过期或未保留。
- YARN 当前 scheduler 状态不是历史队列快照，不能用于证明过去某次任务受到队列竞争。
- `hive_sql_explain` 是当前环境重新编译得到的预测计划，不是目标历史运行保存的实际算子计划。
- Explain 超过返回上限时 MCP 会保留关键 stage/scan/join/shuffle/sort/window/statistics/write 行并标记 `plan_text_compacted`；直接分析返回内容，不读取 Claude 内部 tool-result 临时文件。
- 原任务 SQL 禁止复制、删减、格式化或手工重构后传给 `hive_sql_validate/explain`。必须使用 `hive_task_sql_validate/explain`；只有待验证的候选改写才传 `sql` 参数。
- 候选 SQL 的编译失败只证明该候选有问题，不能据此断言原任务 SQL 编译失败。结论冲突时以 `hive_task_sql_validate/explain` 对当前任务 SQL 的结果为准。
- `hive_storage_layout_get` 的 ContentSummary 是路径汇总事实；文件分布可能因 `maxFiles` 被采样，必须检查 `complete`。
- `hive_table_freshness_get` 的候选最新分区只来自有界扫描；HDFS `modificationTime` 不能冒充业务数据时间或 SLA 达标证据。
- `hive_table_statistics_get` 缺失或过期本身是事实，但不能据此断言优化器一定选错策略。
- 函数存在性或签名不确定时先调用 `hive_function_search/get`；不得根据模型记忆创造当前集群未注册的 UDF。

## 暂不可调用的能力

- 目标历史运行保存的 Hive operator 到 MR stage 映射。
- HS2 编译、锁、Metastore 等端到端历史时间线。
- 历史时刻的队列容量、集群并发和节点资源快照。
- 业务术语、业务 SLA 和平台登记表关系。

不得调用不存在的工具，也不得把这些缺口包装成已验证事实。
