from app.domain.sql.task_dependencies import analyze_task_dependencies


def test_task_dependencies_builds_upstream_downstream_and_external_inputs() -> None:
    result = analyze_task_dependencies([
        {"id": 1, "name": "清洗", "sql": "insert overwrite table dwd.orders select * from ods.orders"},
        {"id": 2, "name": "汇总", "sql": "insert overwrite table dw.summary select * from dwd.orders"},
        {"id": 3, "name": "报表", "sql": "insert overwrite table ads.report select * from dw.summary"},
    ], target_task_id=2, default_db="default")

    assert result["directUpstream"] == [{
        "taskId": 1, "taskName": "清洗", "updatedAt": None, "tables": ["dwd.orders"],
    }]
    assert result["directDownstream"] == [{
        "taskId": 3, "taskName": "报表", "updatedAt": None, "tables": ["dw.summary"],
    }]
    assert result["transitiveUpstream"][0]["depth"] == 1
    assert result["transitiveDownstream"][0]["depth"] == 1
    assert result["externalInputs"] == []
    assert result["outputs"][0]["consumerTasks"][0]["taskId"] == 3


def test_task_dependencies_reports_external_self_and_parse_failures() -> None:
    result = analyze_task_dependencies([
        {
            "id": 7,
            "name": "原地更新",
            "sql": "insert overwrite table dw.snapshot select * from dw.snapshot join ods.source on snapshot.id=source.id",
        },
        {"id": 8, "name": "损坏任务", "sql": "select from"},
    ], target_task_id=7, default_db="default")

    assert result["externalInputs"] == ["ods.source"]
    assert result["selfDependencies"] == ["dw.snapshot"]
    assert result["parseFailures"][0]["taskId"] == 8
