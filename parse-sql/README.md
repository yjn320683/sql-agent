# parse-sql

`parse-sql` 是纯 Java、无状态的 Hive/Trino/Flink SQL 血缘组件，由 Backend、Data Compare 和
Realtime Backend 共同使用。元数据读取通过 `TableMetadataProvider` 注入，解析器本身不访问网络和数据库。

## API

- `SqlLineageParser.parseStatement(ParseRequest)`：严格解析单条语句。
- `SqlLineageParser.parseScript(ParseRequest)`：解析多语句、Step 脚本和 Flink Statement Set。
- `SqlLineageParser.rewriteTables(...)`：按语法树源码区间改写物理表名。
- `SqlLineageParser.splitStatements(...)`：基于 lexer 安全切分脚本。

`ParseRequest` 可设置方言、默认 Catalog/数据库、严格或容错模式，以及元数据提供器。
`SELECT *`、`alias.*` 和无目标列清单的 INSERT 会使用元数据中的真实字段顺序；元数据不可用时保留
通配符和表级血缘，并返回诊断，不猜测字段。

## 生成 Grammar

ANTLR 生成文件位于 `src/main/java/com/yjn/sqlagent/parsesql/antlr`，Listener 和 Visitor 均纳入正式源码。
修改 `src/main/antlr4` 后执行：

```bash
mvn -pl parse-sql -Pgenerate-antlr generate-sources
```

## 全量只读语料回归

回归不会输出 SQL 正文，只写分类、诊断计数、脱敏 token 类型和异常 SQL 的 SHA-256。元数据既可
通过 HiveServer2 JDBC 提供，也可直接通过 Hive Metastore URI 提供：

```bash
SQL_CORPUS_JDBC_URL='jdbc:mysql://host/database' \
SQL_CORPUS_JDBC_USER='readonly' \
SQL_CORPUS_JDBC_PASSWORD='***' \
SQL_CORPUS_HIVE_JDBC_URL='jdbc:hive2://host:10000/default' \
mvn -pl parse-sql -Psql-corpus -Dtest=SqlCorpusRegressionTest test
```

或：

```bash
SQL_CORPUS_JDBC_URL='jdbc:mysql://host/database' \
SQL_CORPUS_JDBC_USER='readonly' \
SQL_CORPUS_JDBC_PASSWORD='***' \
SQL_CORPUS_HIVE_METASTORE_URIS='thrift://metastore-host:9083' \
mvn -pl parse-sql -Psql-corpus \
  -Dtest=SqlCorpusRegressionTest#parsesAllConfiguredActionSqlWithoutUnhandledException test
```

只验证真实 Metastore 的 `SELECT *` 展开时，额外设置 `SQL_CORPUS_METASTORE_PROBE=true`，并执行
`SqlCorpusRegressionTest#expandsAtLeastOneCorpusWildcardFromConfiguredMetastore`。

连接信息只允许通过环境变量或同名 `sql.corpus.*` JVM 属性提供，不写入源码。未配置语料库时测试
自动跳过。全量报告生成在 `parse-sql/target/sql-corpus-report.txt`。
