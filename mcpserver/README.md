# SQL Agent MCP Server

面向 SQL Agent 的只读 MCP Server，提供 Hive 元数据、HiveServer2 预测 Explain、WebHDFS、YARN ResourceManager、MapReduce JobHistory 和 Data Map 能力。

正式环境部署材料已补齐，推荐使用 Docker 方式发布。部署说明见 `DEPLOYMENT.md`，一键脚本见 `scripts/` 目录。

## 目录结构

```text
src/sql_agent_mcp_server/
├── server.py
├── settings.py
├── common/
└── domains/
    ├── hive_metadata/
    ├── hive_execution/
    ├── hadoop_runtime/
    └── data_map/
```

服务会连接真实 Hive Metastore。启动前需要配置 `HIVE_METASTORE_URI`，例如：

```bash
HIVE_METASTORE_URI=thrift://metastore.example.com:9083
HIVE_METASTORE_DB_URI=
DATA_MAP_DB_URI=
HIVE_SERVER2_URI=hive://hiveserver2.example.com:10000/default
WEBHDFS_URLS=http://namenode.example.com:9870
YARN_RESOURCE_MANAGER_URLS=http://resourcemanager.example.com:8088
MAPREDUCE_JOB_HISTORY_URLS=http://jobhistory.example.com:19888
HADOOP_HTTP_USER=
MCP_HTTP_HOST=127.0.0.1
MCP_HTTP_PORT=8820
MCP_HTTP_PATH=/mcp
```

请在项目根目录创建 `.env`，写入同名变量。服务只从 `.env` 或进程环境变量读取配置。`hive_find_column_usage` 会使用 `HIVE_METASTORE_DB_URI` 直连 Metastore 后端库做字段反查。

## 本地运行

启动 Streamable HTTP MCP Server：

```bash
python -m sql_agent_mcp_server.server
```

如果未做安装，也可以使用：

```bash
PYTHONPATH=src python -m sql_agent_mcp_server.server
```

默认监听地址：

```text
http://127.0.0.1:8820/mcp
```

如果需要给其他机器访问，可以在 `.env` 中把 `MCP_HTTP_HOST` 改为 `0.0.0.0`。

## 已实现工具

- `hive_list_databases`
- `hive_search_tables`
- `hive_get_table`
- `hive_get_columns`
- `hive_get_partitions`
- `hive_get_table_ddl`
- `hive_find_column_usage`
- `hive_table_statistics_get`
- `hive_sql_validate`
- `hive_sql_explain`
- `hive_storage_layout_get`
- `hive_table_freshness_get`
- `yarn_application_diagnostics_get`
- `mapreduce_job_search`
- `mapreduce_job_diagnostics_get`
- `mapreduce_aggregated_logs_get`
- `mapreduce_job_compare`
- `platform_dependency_health_get`

## 配置

环境变量示例见 `.env.example`。当前只支持真实 Hive Metastore，不再提供 mock adapter。

## 正式部署

仓库已提供以下 Docker 正式部署交付物：

- `Dockerfile`
- `docker-compose.yml`
- `scripts/deploy.sh`
- `scripts/redeploy.sh`
- `scripts/status.sh`
- `scripts/logs.sh`
- `scripts/stop.sh`

完整生产部署说明见 `DEPLOYMENT.md`。

## LangChain 调用示例

先启动 MCP Server，再运行示例：

```bash
PYTHONPATH=src /Users/yangjunnan/miniconda3/envs/py_312/bin/python examples/langchain_streamable_http_client.py
```

示例内部使用 `langchain-mcp-adapters`：

```python
from langchain_mcp_adapters.client import MultiServerMCPClient

client = MultiServerMCPClient(
    {
        "datadev": {
            "transport": "streamable_http",
            "url": "http://127.0.0.1:8820/mcp",
        }
    }
)
tools = await client.get_tools()
```

服务端仍使用官方 MCP SDK `FastMCP` 暴露工具；LangChain 位于 Agent/Client 侧，用于通过 Streamable HTTP 发现和调用这些 MCP tools。
