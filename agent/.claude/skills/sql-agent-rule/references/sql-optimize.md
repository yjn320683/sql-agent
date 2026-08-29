# /sql优化

目标：对 Hive on MapReduce SQL 做证据驱动的性能诊断。已运行任务优先分析 JobHistory 与 YARN 事实；未运行任务使用预测 Explain、Metastore、HDFS 布局和 SQL 结构。所有改写必须保护结果契约。

## 输入判断

提取待优化 SQL、默认库、Hive 版本、用户症状、优化目标和可接受改动范围。运行诊断还要提取 Query ID、Application ID、MR Job ID、提交人、队列和时间范围。

必须保持的结果契约包括输出字段及顺序、类型、粒度、重复行、NULL、过滤范围、排序、去重和写入语义。多段 SQL 无法确定目标、缺少待优化 SQL、运行候选不唯一或改写可能改变契约时，调用 `AskUserQuestion`。

普通预优化缺少运行数据时可以降级继续，不要为非阻断数据连续追问。

## 取数流程

先读取 `references/sql-optimize-data.md`、`references/mcp-interfaces.md`、`references/sql-equivalence-checklist.md`，然后按以下顺序取最少必要数据：

1. **定位运行**：有 `jobId/applicationId` 时直接取 `mapreduce_job_diagnostics_get`；只有 Query ID、用户或时间时先调用 `mapreduce_job_search`。一条 Hive SQL 可能对应多个 Job，必须收集完整 Job 集合。
2. **分析 JobHistory**：读取每个 Job 的状态、时间、Map/Reduce Task 分布、Counter、失败/重试和少量异常 Attempt；先找最慢 Job、长尾 Task、shuffle/spill、HDFS IO、CPU/GC 或失败证据。
3. **补充日志与 YARN**：Job 失败、反复重试或 Attempt diagnostics 不足时调用 `mapreduce_aggregated_logs_get` 读取 AM 的 `stderr/syslog` 异常摘要；需要排队、Application attempt、container 规格、退出码和资源秒时调用 `yarn_application_diagnostics_get`。RM 历史缺失不否定 JobHistory 事实。
4. **获取预测计划**：需要分析当前任务 SQL 的分区裁剪、stage 结构或 join/aggregate 计划时，必须调用 `hive_task_sql_explain(taskId)`，让 MCP 服务端直接读取原 SQL。不得复制、删减或重构长 SQL 后调用 `hive_sql_explain`。计划必须称为预测计划，不得称为历史任务实际计划。
5. **核对统计与存储**：只针对 SQL 和热路径涉及的表、分区、join/filter/group 字段调用 `hive_table_statistics_get`、`hive_storage_layout_get` 及必要元数据工具；当问题涉及数据是否近期落盘时调用 `hive_table_freshness_get`，且不得将 HDFS 时间解释为业务 SLA。
6. **历史对比**：用户提供基线与候选 Job 时调用 `mapreduce_job_compare`；`comparable=false` 时不得计算或宣称确定收益。
7. **降级**：运行依赖缺失时按 `PLAN + META + HDFS + SQL`、`META + SQL`、`SQL` 逐级降级，并降低结论强度。

## 诊断流程

1. 明确用户症状和结果契约，区分特定运行、长期波动和运行前预优化。
2. 将总耗时拆为 Job 提交等待、Job 执行和多个 MR stage；HS2 编译/锁历史不可得时明确缺口。
3. 在 JobHistory 中比较各 Job，再分别检查 Map/Reduce Task 的 p50、p95、max、状态、重试与异常 Attempt。
4. 关联 Counter：HDFS/FILE IO、Map/Reduce records、shuffle bytes、spilled records、CPU、GC 和内存。单一 Counter 只构成信号，不能直接决定根因。
5. 用预测 Explain 将 SQL 中的 scan、filter、join、aggregate、sort 和 write 与可能的 MR stage 联系起来；没有历史算子映射时只能写候选关联。
6. 用表/分区/列统计解释规模、NDV、NULL、min/max和统计缺失，用 HDFS 布局解释文件数量、文件大小和启动开销。
7. 形成“症状 → RUN 证据 → PLAN/META/HDFS 解释 → 优化动作 → 验证指标”的原因链。跨层证据不足时标记为候选原因。
8. 读取 `references/sql-optimize-rules.md`，按证据强度、预计收益、语义风险和实施成本排序，主要建议不超过 5 条。
9. 只有通过等价检查时才给可直接替换 SQL；否则给局部方案或明确标注非等价备选。

## 改写边界

- 默认最小改动，不擅自添加 `limit`、删除 `order by`、改变 join type、聚合粒度、去重方式、过滤范围或写入目标。
- `select *` 的完整输出字段未确认时不得臆造字段。
- 不自动添加 mapjoin、skew 或 reducer/container 参数；只有运行、计划与数据事实共同支持时才给有范围和回滚方式的候选方案。
- 不执行 SQL、DDL、DML、`EXPLAIN ANALYZE`，不修改 session/global 配置，不重提任务。
- 模型生成或手工摘取 SQL 的 Explain 失败不能证明原任务 SQL 有错；原任务编译结论只接受 `hive_task_sql_validate/explain` 的结果。
- 没有原任务与候选任务的可比实际运行数据时，不输出“降低 N%”“提升 N 倍”或固定耗时等量化收益；只能说明预期影响的指标和验证方法。
- DDL、分区维护、文件合并或上游表格式调整只能作为需平台评审的外部候选动作，不输出可直接执行的 DDL，也不能包装成当前 SQL 已完成的优化。
- 表注释、主键登记或 Explain 基数不能证明运行数据真实唯一。将去重移动到 join 前后只有在每个右表 join key 的运行数据唯一性已验证时才能称为等价，否则必须标记重复放大和结果选择变化风险。
- 不给伪精确收益百分比；只有可比运行的实际差异才能报告历史变化。

## 输出格式

按实际证据自然组织中文 Markdown，覆盖：

- **诊断结论**：瓶颈层级、主要原因、证据来源及确定/候选状态。
- **运行摘要**：目标 Job/Application、时间范围、关键 Task 分布与 Counter；无运行数据时明确省略。
- **优化方案**：按优先级说明动作、依据、风险和预期观察指标。
- **优化 SQL**：只有语义可证明时输出 `sql` fenced code block。
- **验证方法**：结果等价与性能指标分别如何验证。
- **数据缺口**：不完整接口、采样、过期历史和未实现能力。

## 质量闸门

- 已确认目标 Job 集合，没有把 Application 汇总等同于整条 Hive SQL。
- 已严格区分 JobHistory 实际运行事实、YARN 补充、预测 Explain、元数据和推断。
- 没有用当前队列状态解释历史任务，也没有把预测计划称为历史实际计划。
- 没有把 Task/Counter 单一阈值机械写成根因。
- 改写没有静默改变结果契约；限制和验证方式完整。
