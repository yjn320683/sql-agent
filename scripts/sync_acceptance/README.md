# 实时同步五项隔离验收

`run.py` 使用真实 MySQL 测试库、平台 HTTP 接口、生产 `PaimonSyncCommandBuilder`、实际 CDC Action 和本地 Flink MiniCluster。通过 Paimon Reader 读取已提交的数据断言结果，不使用 Mock 数据。

## 隔离边界

- 从当前本地元数据库的测试 Server `7` 读取连接，在 `realtime_data_dev_test` 中创建 UUID 前缀测试表。
- 新建独立 Server 和两类测试任务定义；不调用任务启动/停止接口，不创建平台运行实例。
- 接口服务使用 `RealtimePerformanceHarnessApplication`，不启用定时调度，不加载离线任务调度器。必须使用这个专用 Harness 的 `8392` 端口，不能直接启动第二个生产应用。
- 本地 Flink 只同步本次测试表，使用独立 MySQL server-id 范围、临时 filesystem Paimon 仓库，串行运行全量和增量。不会向共享 Hive/HDFS 目标表写数据，不占用 YARN 槽位。
- 清理仅涉及本次创建的表、任务和 Server；停止本次本地子进程。报告记录验收前后既有运行实例状态。
- 不覆盖 YARN 提交、集群故障恢复及共享 Hive Catalog 集成。这些不能由本次本地数据链路验收推断通过。

## 运行环境

脚本针对当前开发机路径：项目虚拟环境 `agent/.venv/bin/python`（pymysql）、Flink `/opt/flink-2.2.1`、Hadoop `/opt/hadoop-tools/hadoop`，使用已构建的 `realtime-task-submit/target/realtime-task-submit.jar`。

先构建相关模块并编译辅助 Reader/Runner：

```bash
mvn -o -q -pl realtime-backend,realtime-task-submit -am -DskipTests package
javac -cp '/opt/flink-2.2.1/lib/*:realtime-task-submit/target/realtime-task-submit.jar' -d /private/tmp/sync-acceptance-classes scripts/sync_acceptance/SyncAcceptanceRunner.java
```

Harness 使用 `realtime-backend/target/test-classes`、当前各模块 `target/classes` 以及后端依赖作为 classpath，主类：

```text
com.yjn.sqlagent.realtime.performance.RealtimePerformanceHarnessApplication
--server.port=8392
--spring.profiles.active=local
--spring.flyway.enabled=false
--spring.config.additional-location=file:/opt/project/ai-project/sql-agent/backend/config/
--app.realtime.catalog-conf.metastore=filesystem
--app.realtime.paimon-warehouse=file:///private/tmp/sync-acceptance-api-warehouse
--app.realtime.enabled=false
```

确认 Harness 就绪后执行：

```bash
agent/.venv/bin/python scripts/sync_acceptance/run.py
```

终端输出 `REPORT <path>`。JSON 包含断言、任务 ID、实际数据和清理结果，不包含凭据。原始运行日志权限为 `0600`，不要直接分享原始日志。结束后停止专用 Harness。

## 覆盖场景

1. NULL、空字符串和纯空白分区值转为默认值；空时间计算列转默认分区；非法日期默认值被拒绝。
2. 全量与增量分别保存；同一 Server 各自限额 1、计数 1，超限分别拒绝；全量初始快照与增量 latest-offset 实际验数。
3. 两表分别为 Bucket 2/4，核对真实 Paimon 表选项。
4. `DATETIME(3)` 快照早于、等于、NULL 被过滤，晚 1 毫秒的记录保留；未配置过滤的另一张表保持全量；Binlog 旧时间/NULL 插入与跨分区更新不受过滤。
5. `cast(7,INT)`、`date_format`、链式 `substring`、毫秒 epoch 精度转换验证实际值。

## 本轮发现并修复

源表 Schema 曾把 `DATETIME(3)` 返回成 `datetime`，页面和接口因此保存了丢失精度的过滤类型；CDC 启动会拒绝。`RealtimeServerService` 现在对 DATETIME/TIMESTAMP 保留 `COLUMN_TYPE`，前端已有精度处理可以直接使用。新增 DATETIME(3)、TIMESTAMP(6) 回归测试，验收同时验证旧精度配置被接口拒绝。

## 2026-10-16 验收结果

- 最终测试标记 `e367f450`，Server `15`，全量/增量测试任务 `1206` / `1207`：20 项真实接口及数据断言全部通过。
- 源表、测试 Server 和任务定义已清理，本地 Flink 子进程和专用 Harness 已停止，临时 Paimon 仓库已删除。
- 原有运行实例 `120`、`122`、`278` 验收前后均为 `running`，没有对其发起启停请求。
- 60 项前端相关单测、18 项后端 Schema/同步校验回归通过，`git diff --check` 通过。
- 脱敏报告：`/var/folders/0w/3mb155fd4d307rtr968p2fk00000gq/T/sync-acceptance-p00sg2rc/report.json`。
- 精度修复已在专用 Harness 中验证。当前 `8382` 主服务未重启，尚未加载本轮精度修复；本轮没有验证 YARN 提交/恢复链路。
