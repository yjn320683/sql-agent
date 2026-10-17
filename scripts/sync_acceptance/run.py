#!/usr/bin/env python3
"""用独立源表、平台测试定义和本地 Flink 验收五项同步功能；不启停既有任务。"""

import copy
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import ProxyHandler, Request, build_opener

import pymysql

ROOT = Path(__file__).resolve().parents[2]
CP = f"/private/tmp/sync-acceptance-classes:/opt/flink-2.2.1/lib/*:{ROOT}/realtime-task-submit/target/realtime-task-submit.jar"
CP += ":/opt/hadoop-tools/hadoop/share/hadoop/common/*:/opt/hadoop-tools/hadoop/share/hadoop/common/lib/*"
CP += ":/opt/hadoop-tools/hadoop/share/hadoop/hdfs/*:/opt/hadoop-tools/hadoop/share/hadoop/hdfs/lib/*"
JAVA = ["java", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "-cp", CP, "SyncAcceptanceRunner"]


def metadata_connection():
    """读取本地连接配置，凭据仅在内存中使用。"""
    text = (ROOT / "backend/config/application-local.yml").read_text()

    def value(key):
        raw = re.search(r"^    " + key + r":\s*(.+)$", text, re.M)[1].strip().strip("\"'")
        match = re.fullmatch(r"\$\{([^:}]+)(?::(.*))?\}", raw)
        return os.environ.get(match[1], match[2]) if match else raw

    uri = urlparse(value("url").removeprefix("jdbc:"))
    return pymysql.connect(host=uri.hostname, port=uri.port or 3306, user=value("username"),
                           password=value("password"), database=uri.path[1:], autocommit=True,
                           cursorclass=pymysql.cursors.DictCursor)


class Acceptance:
    """管理本次创建的资源，失败时也只清理本次资源。"""

    def __init__(self):
        self.marker = "e" + uuid.uuid4().hex[:7]
        self.server_prefix = self.marker.translate(str.maketrans("0123456789", "ghijklmnop"))
        self.directory = Path(tempfile.mkdtemp(prefix="sync-acceptance-"))
        self.opener = build_opener(ProxyHandler({}))
        self.meta = metadata_connection()
        self.source = None
        self.server = None
        self.tasks = []
        self.tables = []
        self.process = None
        self.report = {"marker": self.marker, "checks": [], "tasks": self.tasks,
                       "runtime": "isolated local Flink; filesystem Paimon catalog"}
        with self.meta.cursor() as cursor:
            cursor.execute("SELECT id,task_id,status FROM rt_task_instance WHERE status='running'")
            self.baseline = cursor.fetchall()

    def api(self, path, payload=None, method=None):
        """调用禁用所有调度器的隔离 Harness API。"""
        request = Request("http://127.0.0.1:8392" + path,
                          data=None if payload is None else json.dumps(payload).encode(),
                          headers={"Content-Type": "application/json"},
                          method=method or ("GET" if payload is None else "POST"))
        try:
            with self.opener.open(request, timeout=60) as response:
                result = json.load(response)
        except HTTPError as error:
            raise AssertionError(error.read().decode()[:500]) from error
        if result.get("code") != 0:
            raise AssertionError(str(result)[:500])
        return result.get("data")

    def check(self, name, condition, evidence=None):
        """记录断言和脱敏证据。"""
        self.report["checks"].append({"name": name, "passed": bool(condition), "evidence": evidence})
        print(name, "PASS" if condition else "FAIL", flush=True)
        assert condition, name

    def reject(self, name, payload, expected):
        """验证明确的服务端错误，避免把连接错误误当校验成功。"""
        try:
            task = self.api("/v1/api/tasks/create", payload)
            self.tasks.append(task)
        except AssertionError as error:
            self.check(name, expected in str(error), str(error))
            return
        self.check(name, False, "unexpected task creation")

    def prepare(self):
        """复制测试库连接为独立 Server，并创建唯一命名的空表。"""
        with self.meta.cursor() as cursor:
            cursor.execute("SELECT address,account,password,database_name FROM rt_server WHERE id=7")
            self.credentials = cursor.fetchone()
        self.database = self.credentials["database_name"]
        uri = urlparse("mysql://" + self.credentials["address"].removeprefix("jdbc:mysql://"))
        self.source = pymysql.connect(host=uri.hostname, port=uri.port or 3306,
                                     user=self.credentials["account"], password=self.credentials["password"],
                                     database=self.database, autocommit=True)
        for suffix in ("a", "b", "i", "x", "y"):
            name = self.marker + "_" + suffix
            with self.source.cursor() as cursor:
                cursor.execute(f"CREATE TABLE `{name}` (id INT PRIMARY KEY, p VARCHAR(30), created_at DATETIME(3), epoch_ms BIGINT)")
            self.tables.append(name)
        server = self.api("/api/servers", {"name": "ACCEPTANCE-" + self.marker, "type": "mysql",
                          "address": self.credentials["address"], "databaseName": self.database,
                          "databasePrefix": self.server_prefix, "account": self.credentials["account"],
                          "password": self.credentials["password"], "description": "isolated acceptance"})
        self.server = server["id"] if isinstance(server, dict) else server
        self.report["serverId"] = self.server
        self.report["sourceTables"] = self.tables
        rows = [(1, "old", "2026-09-30 23:59:59.999", 1790812800000),
                (2, "equal", "2026-10-01 00:00:00", 1790812800000),
                (3, None, None, 1790812800000),
                (4, None, "2026-10-01 00:00:00.001", 1790812800000),
                (5, "", "2026-10-02 00:00:00", 1790812800000),
                (6, "   ", "2026-10-03 00:00:00", 1790812800000),
                (7, "valid", "2026-10-04 00:00:00", 1790812800000)]
        with self.source.cursor() as cursor:
            for table in self.tables[:3]:
                cursor.executemany(f"INSERT INTO `{table}` VALUES (%s,%s,%s,%s)", rows)

    def payload(self, mode, tables):
        """构建与页面相同的请求体，分区及计算列配置逐表保存。"""
        configs = {}
        for index, table in enumerate(tables):
            configs[table] = {"bucket": 2 if index == 0 else 4,
                             "primaryKeys": ["id", "p", "month"], "partitionKeys": ["p", "month"],
                             "partitionDefaults": {"version": 1, "values": {"p": "unknown", "month": "999912"}},
                             "computedColumns": ["day=date_format(created_at,yyyyMMdd)", "month=substring(day,0,6)",
                                                 "constant=cast(7,INT)", "epoch_day=date_format(epoch_ms,yyyyMMdd,3)"]}
        if mode == "full_incremental":
            configs[tables[0]]["timeFilter"] = {"version": 1, "field": "created_at", "sourceType": "datetime(3)",
                                                "start": "2026-10-01 00:00:00", "timeZone": "Asia/Shanghai"}
        return {"taskType": "sync", "name": "ACCEPTANCE-" + self.marker + "-" + mode,
                "owner": "900001", "description": "isolated acceptance; never started on YARN",
                "flinkVersion": "2.2.1", "alarmConfig": {"alarmType": "task_failure", "alarmGroup": "acceptance"},
                "flinkConf": {"parallelism": 1, "checkpointIntervalSeconds": 2,
                              "taskManagerMemoryGb": 1, "jobManagerMemoryGb": 1},
                "taskConfig": {"sourceServerId": self.server, "sourceType": "mysql-cdc", "syncMode": mode,
                               "cdcConfig": {"databaseName": self.database, "selectedTables": tables,
                                             "domainPrefix": self.marker, "typeMappings": ["to-nullable"],
                                             "tableConfigs": configs, "mysqlConfOverrides": {},
                                             "tableConfOverrides": {"bucket": "1", "sink.parallelism": "1"}}}}

    def validate_api(self):
        """真实创建两种测试定义，验证独立限额和错误配置拦截。"""
        full = self.payload("full_incremental", self.tables[:2])
        incremental = self.payload("incremental", [self.tables[2]])
        invalid = copy.deepcopy(full)
        invalid["taskConfig"]["cdcConfig"]["tableConfigs"][self.tables[0]]["bucket"] = 0
        self.reject("invalid bucket", invalid, "Bucket")
        invalid = copy.deepcopy(full)
        invalid["taskConfig"]["cdcConfig"]["tableConfigs"][self.tables[0]]["timeFilter"]["sourceType"] = "datetime"
        self.reject("stale source temporal precision", invalid, "类型已变化")
        invalid = copy.deepcopy(incremental)
        invalid["taskConfig"]["cdcConfig"]["tableConfigs"][self.tables[2]]["timeFilter"] = full["taskConfig"]["cdcConfig"]["tableConfigs"][self.tables[0]]["timeFilter"]
        self.reject("incremental forbids snapshot filter", invalid, "全量")
        invalid = copy.deepcopy(full)
        table = invalid["taskConfig"]["cdcConfig"]["tableConfigs"][self.tables[0]]
        table["partitionKeys"].append("day")
        table["primaryKeys"].append("day")
        table["partitionDefaults"]["values"]["day"] = "20260230"
        self.reject("invalid date default", invalid, "Invalid date")
        for payload in (full, incremental):
            task = self.api("/v1/api/tasks/create", payload)
            self.tasks.append(task)
            detail = self.api(f"/v1/api/tasks/{task}/detail")
            saved = detail["taskConfig"]
            self.check("persisted " + payload["taskConfig"]["syncMode"],
                       saved["syncMode"] == payload["taskConfig"]["syncMode"]
                       and saved["cdcConfig"]["tableConfigs"] == payload["taskConfig"]["cdcConfig"]["tableConfigs"],
                       {"taskId": task})
            # 真实落表执行必须使用平台保存后回读的配置，覆盖持久化转换链路。
            payload["taskConfig"] = saved
        self.reject("independent full quota", self.payload("full_incremental", [self.tables[3]]), "限")
        self.reject("independent incremental quota", self.payload("incremental", [self.tables[4]]), "限")
        availability = self.api("/api/tasks/sync/source-server-availability")
        rows = [row for row in availability if row.get("serverId") == self.server]
        self.check("independent server counts", len(rows) == 2 and all(
            row["currentTaskCount"] == 1 and row["maxTaskCount"] == 1 and not row["available"] for row in rows), rows)
        return full, incremental

    def start(self, payload, mode):
        """经生产 CommandBuilder 构建参数，在本地 MiniCluster 中运行真实 CDC Action。"""
        warehouse = (self.directory / mode).as_uri()
        config = copy.deepcopy(payload["taskConfig"])
        config["cdcConfig"]["mysqlConfOverrides"] = {"scan.startup.mode": "initial" if mode == "full" else "latest-offset",
                                                    "server-time-zone": "Asia/Shanghai"}
        spec = {"taskId": self.tasks[0 if mode == "full" else 1], "taskInstanceId": 40000000 + int(self.marker[1:], 16) % 1000000,
                "startType": "direct", "task": {"taskType": "sync", "sourceType": "mysql-cdc", "parallelism": 1,
                                                  "checkpointInterval": 2, "taskConfig": config},
                "runtimeConfig": {"paimonActionJarPath": "/opt/flink-2.2.1/lib/paimon-flink-action-1.4.2.jar",
                                  "paimonWarehouse": warehouse, "targetDatabase": "acceptance",
                                  "catalogConf": {"metastore": "filesystem"},
                                  "defaultTableConf": {"write-buffer-size": "8 mb", "target-file-size": "8 mb"}},
                "servers": [{"id": self.server, "databaseName": self.database, "databasePrefix": self.server_prefix,
                             **{key: self.credentials[key] for key in ("address", "account", "password")}}]}
        self.log = self.directory / (mode + ".log")
        with self.log.open("w") as stream:
            self.log.chmod(0o600)
            self.process = subprocess.Popen(JAVA + ["run"], stdin=subprocess.PIPE, stdout=stream, stderr=stream)
        self.process.stdin.write(json.dumps(spec).encode())
        self.process.stdin.close()
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError(f"local Flink exited {self.process.returncode}; private log {self.log}")
            if "ACCEPTANCE_JOB=" in self.log.read_text():
                break
            time.sleep(2)
        else:
            raise AssertionError("local Flink startup timeout")
        return warehouse

    def read_until(self, warehouse, expected):
        """轮询已提交的真实 Paimon 数据，精确匹配每张表的 ID 集合。"""
        deadline = time.monotonic() + 150
        last = None
        while time.monotonic() < deadline:
            result = subprocess.run(JAVA + ["read", warehouse], capture_output=True, text=True, timeout=30)
            line = next((line for line in result.stdout.splitlines() if line.startswith("ACCEPTANCE_DATA=")), None)
            if line:
                last = json.loads(line.split("=", 1)[1])
                found = {table: next((item for item in last if item["table"].endswith("_" + table)), None) for table in expected}
                if all(item and {int(row["id"]) for row in item["rows"]} == expected[table] for table, item in found.items()):
                    return found
            if self.process.poll() is not None:
                raise AssertionError(f"Flink terminated; private log {self.log}")
            time.sleep(3)
        self.report["lastRead"] = last
        raise AssertionError("Paimon rows did not match expected IDs")

    def stop(self):
        """仅终止脚本启动的本地子进程。"""
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self.process = None

    def validate_data(self, full, incremental):
        """先验快照，再写入旧时间 Binlog，最后验证纯增量忽略存量。"""
        a, b, inc = self.tables[:3]
        warehouse = self.start(full, "full")
        data = self.read_until(warehouse, {a: {4, 5, 6, 7}, b: set(range(1, 8))})
        self.check("snapshot strictly greater; per-table isolation", True)
        for table, bucket in ((a, "2"), (b, "4")):
            item = data[table]
            self.check("bucket " + table, item["options"]["bucket"] == bucket, item["options"]["bucket"])
            rows = {int(row["id"]): row for row in item["rows"]}
            self.check("NULL/empty/whitespace " + table, all(rows[i]["p"] == "unknown" for i in (4, 5, 6)))
            self.check("computed constant/chain/precision " + table,
                       rows[7]["constant"] == "7" and rows[7]["month"] == "202610" and rows[7]["epoch_day"] == "20261001", rows[7])
        self.check("computed null default", next(row for row in data[b]["rows"] if row["id"] == "3")["month"] == "999912")
        with self.source.cursor() as cursor:
            cursor.execute(f"INSERT INTO `{a}` VALUES (8,NULL,'2020-01-01',1790812800000),(9,'',NULL,1790812800000)")
            cursor.execute(f"UPDATE `{a}` SET p='updated',created_at='2020-02-01' WHERE id=7")
        data = self.read_until(warehouse, {a: {4, 5, 6, 7, 8, 9}})
        self.check("binlog bypasses time filter", True, data[a]["rows"])
        rows = {int(row["id"]): row for row in data[a]["rows"]}
        self.check("binlog update moves partition without duplicates", len(data[a]["rows"]) == 6
                   and rows[7]["p"] == "updated" and rows[7]["month"] == "202002")
        self.report["fullData"] = data
        self.stop()
        warehouse = self.start(incremental, "incremental")
        time.sleep(15)
        with self.source.cursor() as cursor:
            cursor.execute(f"INSERT INTO `{inc}` VALUES (10,'','2026-10-05',1790812800000)")
        data = self.read_until(warehouse, {inc: {10}})
        self.check("incremental latest-offset excludes existing rows", True, data[inc]["rows"])
        self.report["incrementalData"] = data
        self.stop()

    def cleanup(self):
        """停止本次进程、删除本次任务/Server/源表，核对已有实例状态。"""
        self.stop()
        errors = []
        for task in self.tasks:
            try:
                self.api(f"/v1/api/tasks/{task}/delete", {})
            except Exception as error:
                errors.append(f"task {task}: {error}")
        if self.server:
            try:
                self.api(f"/api/servers/{self.server}", method="DELETE")
            except Exception as error:
                errors.append(f"server {self.server}: {error}")
        if self.source:
            with self.source.cursor() as cursor:
                for table in self.tables:
                    assert table.startswith(self.marker + "_")
                    cursor.execute(f"DROP TABLE `{table}`")
            self.source.close()
        with self.meta.cursor() as cursor:
            cursor.execute("SELECT id,task_id,status FROM rt_task_instance WHERE status='running'")
            after = cursor.fetchall()
        self.report["existingRunningInstancesUnchanged"] = self.baseline == after
        self.report["baseline"] = self.baseline
        self.report["cleanupErrors"] = errors
        self.meta.close()
        # 数据证据已存入 report，只删除本次临时目录中的独立 Paimon 仓库。
        for mode in ("full", "incremental"):
            warehouse = self.directory / mode
            if warehouse.exists():
                shutil.rmtree(warehouse)
        report = self.directory / "report.json"
        report.write_text(json.dumps(self.report, indent=2, ensure_ascii=False))
        print("REPORT", report, flush=True)


def main():
    """依次准备、校验平台接口、运行数据验收，始终执行资源清理。"""
    acceptance = Acceptance()
    try:
        acceptance.prepare()
        full, incremental = acceptance.validate_api()
        acceptance.validate_data(full, incremental)
    finally:
        acceptance.cleanup()


if __name__ == "__main__":
    main()
