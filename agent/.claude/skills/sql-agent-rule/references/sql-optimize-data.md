# SQL 优化数据与诊断流程

本文件仅用于 `/sql优化`，定义 Hive on MapReduce 的数据层次、归一化要求和结论边界。工具是否可调用以 `mcp-interfaces.md` 为准。

## 证据层次

| 标签 | 来源 | 能确认的内容 |
| --- | --- | --- |
| `SQL` | `sql_task_get` 返回的任务 SQL、任务描述与用户要求 | 结构、显式谓词、结果契约和用户症状 |
| `RUN` | MapReduce JobHistory、Task、Attempt、Counter | 已完成 Job 的耗时、长尾、IO、shuffle、spill、资源、失败与重试 |
| `YARN` | ResourceManager Application/attempt/container | 排队、Application 状态、资源秒、container 规格和退出状态 |
| `PLAN` | HiveServer2 只读 Explain | 当前环境重新编译得到的预测 stage/算子计划 |
| `META` | Metastore、Data Map、表/分区/列统计 | 字段、分区、主键记录、存储属性、规模和统计质量 |
| `HDFS` | WebHDFS | 表/分区路径大小、文件数量和有界文件分布 |
| `HYP` | Claude 关联推断 | 尚需计划、统计或复跑验证的候选原因 |

`RUN` 说明目标 Job 实际发生了什么；`PLAN/META/HDFS/YARN`解释可能原因；`SQL` 和业务契约决定允许怎样改。关键根因应尽量同时具有运行证据和解释证据。

## 三种模式

| 模式 | 最小数据 | 结论边界 |
| --- | --- | --- |
| 特定运行根因 | SQL、完整 Job 集合、JobHistory 画像 | 可定位该次运行的主要瓶颈，不自动外推 |
| 历史对比 | 两组 Job、输入指纹、用户、队列和关键配置 | 只有 `mapreduce_job_compare.comparable=true` 才报告差异 |
| 运行前预优化 | SQL、预测 Explain、元数据；统计和 HDFS 更好 | 只能说明计划/结构风险，不声称真实瓶颈 |

## 标识链

使用 `Hive Query ID → MR Job ID/YARN Application ID → Map/Reduce Task → Task Attempt/Container`。

- 一条 Hive SQL 可能生成多个顺序或依赖 MR Job，诊断必须覆盖完整 Job 集合。
- MapReduce 的 `application_<timestamp>_<id>` 与 `job_<timestamp>_<id>`可以转换，但仍需用 Query ID、Job 配置指纹、用户和时间确认目标。
- 只有 Query ID 时用窄时间范围搜索 JobHistory；候选不唯一时必须澄清。
- JobHistory 记录已完成任务；RM 的 Application/container 历史可能提前过期，缺失要标记但不能伪造。

## 分阶段取数

### 1. 保护结果契约

记录原始 SQL、模板变量、默认库、输出字段与顺序、类型、粒度、重复行、NULL、时间范围、排序、去重和写入目标。任何候选改写先经过等价检查。

### 2. 定位 Job

优先级：精确 Job ID → 精确 Application ID → Query ID 加窄时间窗 → 用户/队列/名称加时间范围。记录匹配依据和完整性，不根据名称相似度擅自选中。

### 3. JobHistory 热路径

每个 Job 至少读取：

- submit/start/finish、state、diagnostics、Map/Reduce 数量。
- Map 与 Reduce Task duration 的 count/min/p50/p95/max 和状态分布。
- HDFS/FILE bytes、Map/Reduce records、shuffle bytes、spilled records、CPU、GC、物理/虚拟内存等可用 Counter。
- 少量最慢或失败 Task 的 Attempt、节点、container 和诊断。

先比较多个 Job，再下钻最慢、失败或长尾 Job。Counter 缺失可能是版本、配置或历史保留差异，必须显式标记。

### 4. YARN 补充

按 Application 获取 queue、state/finalStatus、submit/start/launch/finish、attempt、container、memory/vcore-seconds、抢占和退出状态。

仅在字段真实存在时计算排队/运行时长。当前 scheduler 页面不能还原历史运行时刻的队列竞争，禁止据此归因。

### 5. 预测 Explain

首轮只运行普通 `EXPLAIN`，只有普通计划缺少解决当前问题的必要字段时才使用 `EXPLAIN EXTENDED`；绝不运行 `EXPLAIN ANALYZE`。记录 `planSource=predicted`、默认库、编译耗时和 `complete/missingReasons`。超长计划会由 MCP 保留关键执行信号并压缩，禁止尝试读取 Claude 内部 tool-result 临时文件。预测计划用于解释当前 SQL 可能的 stage、scan、join、aggregate、sort 和 write，不冒充历史 Job 的保存计划。

### 6. 统计与元数据

只针对相关表、分区和 join/filter/group 字段读取：

- 表/分区 row count、总大小、文件数、统计时间和准确性标记。
- 列 NDV、NULL、min/max、长度和可用类型统计。
- 分区字段、DDL、存储格式、主键登记。

统计缺失或过期可作为风险事实，但不能直接证明某个 join 策略错误。

### 7. HDFS 布局

ContentSummary 提供路径总大小、空间占用、文件数和目录数；有界目录遍历提供文件大小分布。`sampledFileCount < fileCount` 或 `complete=false` 时只能描述采样分布。文件多且 Task 短可支持启动开销候选，仍需结合 JobHistory。

## 数据质量

- 时间戳使用来源原始毫秒值，持续时间统一为 `ms`。
- bytes、records、CPU、GC、memory/vcore-seconds保留整数原值，不让模型猜单位。
- 分布使用 count/min/p50/p95/max；样本为空或不足时返回空值而非伪造分位数。
- 所有响应检查 `source`、`fetchedAt`、`complete`、`missingReasons` 和 `warnings`。
- 配置只保留诊断白名单；SQL 和输入路径只返回 SHA-256 指纹，不发送原文。
- 不设置跨版本、跨队列通用阈值；优先比较同一 Job 内部、同一查询多个 Job，以及明确可比的历史运行。

## 原因链

主要结论使用：`用户症状 → RUN/YARN 证据 → PLAN/META/HDFS 解释 → 优化动作 → 验证指标`。

只有 SQL 结构信号时写“风险”；只有运行相关性但没有计划/数据解释时写“候选原因”；只有跨层证据吻合时写主要根因。

## 验证

- **结果等价**：schema、行数、关键分组、NULL/重复、聚合值、时间边界和写入范围一致。
- **性能对比**：在代表性输入和可比环境下比较总/Job/Task 耗时、HDFS IO、shuffle、spill、p95/max、CPU/GC、资源秒和失败/重试。
- `mapreduce_job_compare.comparable=false` 时先解释不可比项，不宣称优化收益。
