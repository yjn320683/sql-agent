"""基于已保存任务 SQL 构建跨任务依赖与影响范围。"""

from __future__ import annotations

from collections import defaultdict, deque
from typing import Any

from app.domain.sql.lineage import analyze_sql_lineage


def analyze_task_dependencies(
    tasks: list[dict[str, Any]],
    target_task_id: int,
    default_db: str | None = None,
) -> dict[str, Any]:
    parsed: dict[int, dict[str, Any]] = {}
    nodes: dict[int, dict[str, Any]] = {}
    parse_failures: list[dict[str, Any]] = []

    for task in tasks:
        task_id = int(task["id"])
        nodes[task_id] = {
            "taskId": task_id,
            "taskName": str(task.get("name") or f"任务 #{task_id}"),
            "updatedAt": task.get("update_time") or task.get("updatedAt"),
        }
        try:
            parsed[task_id] = analyze_sql_lineage(str(task.get("sql") or ""), default_db)
        except ValueError as exc:
            parse_failures.append({
                **nodes[task_id],
                "reason": str(exc),
            })

    if target_task_id not in nodes:
        raise ValueError(f"任务 {target_task_id} 不在本次真实任务扫描范围内。")
    target_lineage = parsed.get(target_task_id)
    if target_lineage is None:
        failure = next(item for item in parse_failures if item["taskId"] == target_task_id)
        raise ValueError(f"目标任务 SQL 无法解析：{failure['reason']}")

    producers: dict[str, list[int]] = defaultdict(list)
    for task_id, lineage in parsed.items():
        for output in lineage["outputs"]:
            key = _qualified_key(output)
            if key and task_id not in producers[key]:
                producers[key].append(task_id)

    edges: list[dict[str, Any]] = []
    self_dependencies: list[str] = []
    external_inputs: list[str] = []
    target_input_names = {_qualified_key(item) for item in target_lineage["inputs"]}
    for downstream_id, lineage in parsed.items():
        for input_table in lineage["inputs"]:
            table_name = _qualified_key(input_table)
            if not table_name:
                continue
            table_producers = producers.get(table_name, [])
            if downstream_id == target_task_id and not table_producers:
                external_inputs.append(table_name)
            for upstream_id in table_producers:
                if upstream_id == downstream_id:
                    if downstream_id == target_task_id:
                        self_dependencies.append(table_name)
                    continue
                edges.append({
                    "upstreamTaskId": upstream_id,
                    "downstreamTaskId": downstream_id,
                    "table": table_name,
                })

    edges = _deduplicate_edges(edges)
    direct_upstream = _related_nodes(edges, nodes, target_task_id, upstream=True)
    direct_downstream = _related_nodes(edges, nodes, target_task_id, upstream=False)
    transitive_upstream = _walk(edges, nodes, target_task_id, upstream=True)
    transitive_downstream = _walk(edges, nodes, target_task_id, upstream=False)

    output_impacts = []
    for output in target_lineage["outputs"]:
        table_name = _qualified_key(output)
        consumers = sorted({
            edge["downstreamTaskId"] for edge in edges
            if edge["upstreamTaskId"] == target_task_id and edge["table"] == table_name
        })
        output_impacts.append({
            "table": table_name,
            "consumerTasks": [nodes[task_id] for task_id in consumers],
        })

    producer_conflicts = [
        {
            "table": table,
            "producerTasks": [nodes[task_id] for task_id in task_ids],
        }
        for table, task_ids in sorted(producers.items())
        if len(task_ids) > 1 and (table in target_input_names or target_task_id in task_ids)
    ]
    return {
        "target": nodes[target_task_id],
        "directUpstream": direct_upstream,
        "directDownstream": direct_downstream,
        "transitiveUpstream": transitive_upstream,
        "transitiveDownstream": transitive_downstream,
        "externalInputs": sorted(set(external_inputs)),
        "selfDependencies": sorted(set(self_dependencies)),
        "outputs": output_impacts,
        "producerConflicts": producer_conflicts,
        "parseFailures": parse_failures,
        "parsedTaskCount": len(parsed),
        "edges": edges,
    }


def _qualified_key(reference: dict[str, Any]) -> str:
    return str(reference.get("qualifiedName") or "").strip().lower()


def _deduplicate_edges(edges: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    seen = set()
    for edge in edges:
        key = (edge["upstreamTaskId"], edge["downstreamTaskId"], edge["table"])
        if key in seen:
            continue
        seen.add(key)
        result.append(edge)
    return sorted(result, key=lambda item: (item["upstreamTaskId"], item["downstreamTaskId"], item["table"]))


def _related_nodes(
    edges: list[dict[str, Any]],
    nodes: dict[int, dict[str, Any]],
    target_task_id: int,
    *,
    upstream: bool,
) -> list[dict[str, Any]]:
    relation_id = "upstreamTaskId" if upstream else "downstreamTaskId"
    target_id = "downstreamTaskId" if upstream else "upstreamTaskId"
    grouped: dict[int, set[str]] = defaultdict(set)
    for edge in edges:
        if edge[target_id] == target_task_id:
            grouped[edge[relation_id]].add(edge["table"])
    return [
        {**nodes[task_id], "tables": sorted(tables)}
        for task_id, tables in sorted(grouped.items())
    ]


def _walk(
    edges: list[dict[str, Any]],
    nodes: dict[int, dict[str, Any]],
    target_task_id: int,
    *,
    upstream: bool,
) -> list[dict[str, Any]]:
    source_key = "downstreamTaskId" if upstream else "upstreamTaskId"
    next_key = "upstreamTaskId" if upstream else "downstreamTaskId"
    adjacency: dict[int, set[int]] = defaultdict(set)
    for edge in edges:
        adjacency[edge[source_key]].add(edge[next_key])

    queue = deque((task_id, 1) for task_id in sorted(adjacency[target_task_id]))
    depths: dict[int, int] = {}
    while queue:
        task_id, depth = queue.popleft()
        if task_id == target_task_id or task_id in depths:
            continue
        depths[task_id] = depth
        queue.extend((next_id, depth + 1) for next_id in sorted(adjacency[task_id]))
    return [{**nodes[task_id], "depth": depth} for task_id, depth in sorted(depths.items(), key=lambda item: (item[1], item[0]))]
