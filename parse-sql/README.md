# parse-sql

该模块作为 Backend 的内部依赖随 Backend 一起部署，不单独启动或部署。

ANTLR 生成的 Java 文件位于：

`src/main/java/com/yjn/sqlagent/parsesql/antlr`

这些文件属于正式源码，可由 Maven 直接编译。修改 `src/main/antlr4` 下的语法文件后，使用以下命令重新生成：

```bash
mvn -pl parse-sql -Pgenerate-antlr generate-sources
```

生成代码使用 ANTLR 4.13.2，保留 Listener，不生成 Visitor。`.interp` 和 `.tokens` 是生成工具的辅助文件，不纳入版本管理。
