# parse-sql 血缘解析重构实施报告

## 完成范围

- 使用 ANTLR Visitor 重构为 `SqlLineageParser` 统一入口，解析器无共享可变状态，元数据通过
  `TableMetadataProvider` 注入。
- 提供单语句解析、脚本解析、Lexer 安全切分、注释清理和基于语法树源码区间的表名改写。
- 提供不可变的脚本、语句、表访问、列血缘、字段用途、Join 关系和诊断结果模型。
- 支持 Hive、Trino 和 Flink 模式，以及 SELECT/WITH、INSERT/REPLACE、CTAS、CREATE VIEW、
  CREATE TEMPORARY VIEW、UPDATE、DELETE、SET、USE 和 Flink Statement Set。
- 建立 CTE、子查询、派生表、集合运算和关联子查询作用域；支持 JOIN/WHERE/GROUP BY/HAVING/
  ORDER BY/WINDOW/PARTITION WRITE/UPDATE SET 字段用途。
- `SELECT *`、`alias.*`、无目标字段清单 INSERT 均通过元数据按真实字段顺序展开。元数据缺失时
  保留表级血缘和通配符占位，并返回明确诊断。
- 未限定的歧义字段关联全部候选来源，并同时产生歧义诊断。
- Backend、Data Compare 和 Realtime Backend 已迁移到统一 API；Realtime 额外对比 ANTLR 血缘与
  Flink Planner 的输入、输出集合。
- 旧 `HiveSqlParser`、`SqlParseResult`、`TableReference` 和 `TableRole` API 已删除。

## 元数据接入

- Backend：通过 Agent 的 Hive 字段接口读取普通字段与分区字段。
- Data Compare：通过 `HiveJdbcClient.columns()` 读取字段并保持顺序。
- Realtime Backend：通过实时表管理元数据读取 Paimon Schema。
- 同一物理表在一次脚本解析中最多读取一次；Flink 临时视图会注册为脚本内虚拟 Schema。

## 自动化验证

- `parse-sql`：34 个测试，32 个本地测试通过，2 个显式外部连接测试在普通构建中按设计跳过；
  两个外部测试已分别使用真实语料库和 Hive Metastore 单独执行通过。
- `datacompare`：24 个测试通过。
- `realtime-task-submit`：8 个测试通过。
- `realtime-backend`：70 个测试通过。
- `backend`：61 个测试通过。
- 已覆盖星号展开、CTE/子查询、关联子查询、集合运算、窗口、LATERAL VIEW、UNNEST、Lambda、
  动静态分区、动态表名、Statement Set、改写安全、并发确定性和三种元数据适配器。
- 根项目 `mvn clean test` 已通过，7 个 Reactor 项目全部构建成功；共 195 个本地测试通过，
  2 个外部连接测试按设计跳过。

## 真实语料回归状态

已实现并实际执行 `SqlCorpusRegressionTest`，查询能力等价于参考项目
`JdbcUtil.queryActionSqlList()`。测试只执行只读查询，不输出 SQL 正文；报告只包含分类、诊断计数、
脱敏 token 类型和 SHA-256。

最终使用用户提供的语料连接与参考项目配置的 Hive Metastore 完成 6679 条 SQL 全量回归：

- 完全成功 99，部分成功 376，明确无效 351，明确不支持 5853。
- 解析超时 0，未捕获异常 0。
- `SELECT *` 已通过真实 Metastore Schema 验证按字段顺序展开；单独的展开探测成功展开 3 列。
- 接入真实 Metastore 后，`METADATA_UNAVAILABLE` 从无元数据基线的 64327 次降至 15010 次，
  `WILDCARD_NOT_EXPANDED` 从 6331 次降至 2173 次。
- 真实语料驱动修复了 `INSERT ... VALUES` 行构造器被误判为单列、DELETE 目标别名、Hive SET 原始值、
  缺分号但从新行开始的顶层管理命令恢复，以及超长脚本的流式、并行和超时保护。
- 剩余 97 个语法诊断均保留行列和源码区间：30 个尾随 token、19 个 token 不匹配、19 个多余 token、
  10 个缺失 token、19 个无可行分支。最高频的 21 个同构样本已核对括号深度，报错右括号处深度为 0，
  属于语料多余右括号，不放宽 Grammar 吞错。

Metastore 中不存在的历史表、动态表名或不完整库名仍会部分成功，保留表级血缘和明确元数据诊断。

## 当前明确边界

- 未纳入顶层语句：MERGE、存储过程、权限/管理语句、用户自定义 DDL。容错模式会返回
  `UNSUPPORTED_STATEMENT` 或语法诊断，不会静默吞掉。
- 递归 CTE 可以解析并保留作用域关系，但不会尝试推导递归迭代次数或运行时数据流闭包。
- 动态表名无法对应唯一物理对象时保留未解析表引用；不会猜测实际表名。
- 没有元数据时无法完整展开物理表通配符；返回 `METADATA_UNAVAILABLE` 和
  `WILDCARD_NOT_EXPANDED`，表级关系仍保留。
- Flink 生产语法与物理兼容性最终仍以配置真实 Catalog 的 Flink Parser/Planner 为准。
- 容错语料分类不会把损坏 SQL 伪装成成功；所有无效或不支持语句均保留固定错误码和源码位置。
