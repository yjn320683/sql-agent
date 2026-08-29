"""Hive Metastore adapter 抽象。"""

from __future__ import annotations

from contextlib import contextmanager
from collections.abc import Iterator
import re
from typing import Any, Protocol
from urllib.parse import urlparse

from sql_agent_mcp_server.common.errors import McpDomainError, McpErrorCode
from sql_agent_mcp_server.domains.hive_metadata.adapters.metastore_search import MetastoreSqlSearchAdapter
from sql_agent_mcp_server.domains.hive_metadata.schemas import ColumnMetadata, ColumnUsage, PartitionMetadata, TableMetadata


class HiveMetadataAdapter(Protocol):
    """Hive 元数据访问接口，具体实现可来自 Metastore、DataHub 或 Catalog API。"""

    source: str

    def list_databases(self, *, catalog: str) -> list[str]:
        raise NotImplementedError

    def search_tables(
        self, *, catalog: str, pattern: str, db: str | None, limit: int, offset: int
    ) -> tuple[list[TableMetadata], int]:
        raise NotImplementedError

    def get_table(self, *, catalog: str, db: str, table: str) -> TableMetadata:
        raise NotImplementedError

    def get_columns(self, *, catalog: str, db: str, table: str) -> list[ColumnMetadata]:
        raise NotImplementedError

    def get_partitions(
        self, *, catalog: str, db: str, table: str, limit: int, offset: int
    ) -> tuple[list[PartitionMetadata], int]:
        raise NotImplementedError

    def get_table_ddl(self, *, catalog: str, db: str, table: str) -> str:
        raise NotImplementedError

    def find_column_usage(
        self,
        *,
        catalog: str,
        column: str,
        db: str | None,
        table_pattern: str,
        limit: int,
        offset: int,
    ) -> tuple[list[ColumnUsage], int]:
        raise NotImplementedError

    def get_table_statistics(
        self, *, catalog: str, db: str, table: str, partitions: list[str], columns: list[str]
    ) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]], list[str]]:
        raise NotImplementedError

    def get_storage_locations(
        self, *, catalog: str, db: str, table: str, partitions: list[str]
    ) -> tuple[TableMetadata, list[str]]:
        raise NotImplementedError


class RealHiveMetastoreAdapter:
    """通过 Hive Metastore Thrift API 查询真实元数据。"""

    source = "hive-metastore"

    def __init__(self, uri: str | None, db_uri: str | None = None, *, timeout_seconds: int = 10) -> None:
        self.uri = uri
        self.timeout_seconds = timeout_seconds
        self.search_adapter = MetastoreSqlSearchAdapter(db_uri, timeout_seconds=timeout_seconds)

    def list_databases(self, *, catalog: str) -> list[str]:
        with self._client() as client:
            return sorted(client.get_all_databases())

    def search_tables(
        self, *, catalog: str, pattern: str, db: str | None, limit: int, offset: int
    ) -> tuple[list[TableMetadata], int]:
        with self._client() as client:
            dbs = [db] if db else sorted(client.get_all_databases())
            identifiers: list[tuple[str, str]] = []
            table_pattern = f".*{re.escape(pattern)}.*" if pattern else ".*"
            for db_name in dbs:
                identifiers.extend((db_name, table_name) for table_name in client.get_tables(db_name, table_pattern))
            identifiers.sort()
            page = identifiers[offset : offset + limit]
            tables = [self._to_table_metadata(client.get_table(db_name, table_name)) for db_name, table_name in page]
            return tables, len(identifiers)

    def get_table(self, *, catalog: str, db: str, table: str) -> TableMetadata:
        with self._client() as client:
            try:
                return self._to_table_metadata(client.get_table(db, table))
            except Exception as exc:  # noqa: BLE001 - Thrift 异常类型随 Hive 版本变化
                self._raise_table_error(exc, db=db, table=table)

    def get_columns(self, *, catalog: str, db: str, table: str) -> list[ColumnMetadata]:
        return self.get_table(catalog=catalog, db=db, table=table).columns or []

    def get_partitions(
        self, *, catalog: str, db: str, table: str, limit: int, offset: int
    ) -> tuple[list[PartitionMetadata], int]:
        with self._client() as client:
            try:
                raw_table = client.get_table(db, table)
                total = int(client.get_num_partitions_by_filter(db, table, ""))
                partition_names = client.get_partition_names(db, table, offset + limit)
                page_names = partition_names[offset : offset + limit]
                partitions = client.get_partitions_by_names(db, table, page_names) if page_names else []
            except Exception as exc:  # noqa: BLE001
                self._raise_table_error(exc, db=db, table=table)
            partition_keys = [item.name for item in raw_table.partitionKeys or []]
            items = [self._to_partition_metadata(item, partition_keys=partition_keys) for item in partitions]
            return items, total

    def get_table_ddl(self, *, catalog: str, db: str, table: str) -> str:
        metadata = self.get_table(catalog=catalog, db=db, table=table)
        return self._build_ddl(metadata)

    def find_column_usage(
        self,
        *,
        catalog: str,
        column: str,
        db: str | None,
        table_pattern: str,
        limit: int,
        offset: int,
    ) -> tuple[list[ColumnUsage], int]:
        return self.search_adapter.find_column_usage(
            catalog=catalog,
            column=column,
            db=db,
            table_pattern=table_pattern,
            limit=limit,
            offset=offset,
        )

    def get_table_statistics(
        self, *, catalog: str, db: str, table: str, partitions: list[str], columns: list[str]
    ) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]], list[str]]:
        warnings: list[str] = []
        with self._client() as client:
            try:
                raw_table = client.get_table(db, table)
                table_stats = self._parameter_statistics(raw_table.parameters or {})
                partition_stats: list[dict[str, Any]] = []
                for partition_name in partitions:
                    raw_partition = client.get_partition_by_name(db, table, partition_name)
                    partition_stats.append(
                        {"partition": partition_name, **self._parameter_statistics(raw_partition.parameters or {})}
                    )
                column_stats: list[dict[str, Any]] = []
                for column in columns:
                    try:
                        raw_stats = client.get_table_column_statistics(db, table, column)
                    except Exception as exc:  # noqa: BLE001 - 未采集列统计在不同版本下异常类型不同
                        if any(marker in type(exc).__name__.lower() for marker in ("nosuch", "notfound", "invalidobject")):
                            warnings.append(f"字段 {column} 没有可用的 Metastore 列统计。")
                            continue
                        if type(exc).__name__ == "TApplicationException" and any(
                            marker in str(exc).lower()
                            for marker in ("unknown result", "unknown method", "invalid method")
                        ):
                            warnings.append("当前 Hive Metastore 不支持列统计 RPC，仅返回表参数统计。")
                            break
                        raise
                    converted = self._column_statistics(raw_stats)
                    if converted is None:
                        warnings.append(f"字段 {column} 没有可用的 Metastore 列统计。")
                    else:
                        column_stats.append(converted)
            except Exception as exc:  # noqa: BLE001
                self._raise_table_error(exc, db=db, table=table)
        if not table_stats:
            warnings.append("表参数中缺少 numRows/totalSize/rawDataSize 等统计。")
        return table_stats, partition_stats, column_stats, warnings

    def get_storage_locations(
        self, *, catalog: str, db: str, table: str, partitions: list[str]
    ) -> tuple[TableMetadata, list[str]]:
        with self._client() as client:
            try:
                raw_table = client.get_table(db, table)
                metadata = self._to_table_metadata(raw_table)
                if partitions:
                    raw_partitions = client.get_partitions_by_names(db, table, partitions)
                    locations = [
                        location
                        for item in raw_partitions
                        if (location := getattr(getattr(item, "sd", None), "location", None))
                    ]
                    if len(locations) != len(partitions):
                        raise McpDomainError(
                            McpErrorCode.NOT_FOUND,
                            "One or more Hive partitions were not found or have no storage location.",
                            details={"requested": len(partitions), "resolved": len(locations)},
                        )
                else:
                    locations = [metadata.location] if metadata.location else []
            except McpDomainError:
                raise
            except Exception as exc:  # noqa: BLE001
                self._raise_table_error(exc, db=db, table=table)
        if not locations:
            raise McpDomainError(
                McpErrorCode.NOT_FOUND,
                "Hive table has no HDFS storage location.",
                details={"db": db, "table": table},
            )
        return metadata, locations

    @staticmethod
    def _build_ddl(metadata: TableMetadata) -> str:
        column_lines = [
            RealHiveMetastoreAdapter._ddl_column_line(col)
            for col in metadata.columns or []
            if not col.partition_key
        ]
        partition_lines = [
            RealHiveMetastoreAdapter._ddl_column_line(col)
            for col in metadata.columns or []
            if col.partition_key
        ]

        create_keyword = "CREATE EXTERNAL TABLE" if metadata.table_type == "EXTERNAL_TABLE" else "CREATE TABLE"
        ddl = [
            f"{create_keyword} {RealHiveMetastoreAdapter._ddl_identifier(metadata.db)}."
            f"{RealHiveMetastoreAdapter._ddl_identifier(metadata.table)} (",
            ",\n".join(column_lines),
            ")",
        ]

        if partition_lines:
            ddl.extend(["PARTITIONED BY (", ",\n".join(partition_lines), ")"])

        if metadata.comment:
            ddl.append(f"COMMENT '{RealHiveMetastoreAdapter._escape_ddl_string(metadata.comment)}'")

        if metadata.serde_class:
            ddl.append(
                "ROW FORMAT SERDE\n"
                f"  '{RealHiveMetastoreAdapter._escape_ddl_string(metadata.serde_class)}'"
            )

        if metadata.input_format and metadata.output_format:
            ddl.append(
                "STORED AS INPUTFORMAT\n"
                f"  '{RealHiveMetastoreAdapter._escape_ddl_string(metadata.input_format)}'\n"
                "OUTPUTFORMAT\n"
                f"  '{RealHiveMetastoreAdapter._escape_ddl_string(metadata.output_format)}'"
            )

        if metadata.location:
            ddl.append(f"LOCATION\n  '{RealHiveMetastoreAdapter._escape_ddl_string(metadata.location)}'")

        if metadata.tbl_properties:
            props = ",\n".join(
                f"  '{RealHiveMetastoreAdapter._escape_ddl_string(k)}'='{RealHiveMetastoreAdapter._escape_ddl_string(v)}'"
                for k, v in sorted(metadata.tbl_properties.items())
            )
            ddl.append(f"TBLPROPERTIES (\n{props})")

        return "\n".join(ddl) + ";"

    @contextmanager
    def _client(self) -> Iterator:
        host, port = self._parse_uri()
        try:
            from hmsclient import HMSClient
        except ImportError as exc:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Hive Metastore client dependency is not installed.",
                details={"required": "hmsclient"},
            ) from exc

        from thrift.protocol import TBinaryProtocol
        from thrift.transport import TSocket, TTransport

        socket = TSocket.TSocket(host, port)
        socket.setTimeout(self.timeout_seconds * 1000)
        transport = TTransport.TBufferedTransport(socket)
        protocol = TBinaryProtocol.TBinaryProtocol(transport)
        client = HMSClient(iprot=protocol)
        try:
            client.open()
            yield client
        except McpDomainError:
            raise
        except Exception as exc:  # noqa: BLE001 - 统一屏蔽底层 Thrift 细节
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Hive Metastore request failed.",
                details={"errorType": type(exc).__name__, "endpoint": f"{host}:{port}"},
            ) from exc
        finally:
            try:
                client.close()
            except Exception:
                pass

    def _parse_uri(self) -> tuple[str, int]:
        if not self.uri:
            raise McpDomainError(
                McpErrorCode.DEPENDENCY_UNAVAILABLE,
                "Hive Metastore URI is not configured.",
                details={"required": "HIVE_METASTORE_URI"},
            )

        raw_uri = self.uri.split(",", 1)[0].strip()
        parsed = urlparse(raw_uri if "://" in raw_uri else f"thrift://{raw_uri}")
        host = parsed.hostname
        port = parsed.port or 9083
        if not host:
            raise McpDomainError(
                McpErrorCode.INVALID_REQUEST,
                "Hive Metastore URI is invalid.",
                details={"uri": raw_uri},
            )
        return host, port

    @classmethod
    def _to_table_metadata(cls, raw_table) -> TableMetadata:
        parameters = raw_table.parameters or {}
        storage = raw_table.sd
        normal_columns = cls._to_columns(getattr(storage, "cols", None) or [], partition_key=False)
        partition_columns = cls._to_columns(raw_table.partitionKeys or [], partition_key=True)
        serde_info = getattr(storage, "serdeInfo", None)
        serde_class = getattr(serde_info, "serializationLib", None) or None
        input_format = getattr(storage, "inputFormat", None) or None
        output_format = getattr(storage, "outputFormat", None) or None
        tbl_properties = {k: v for k, v in parameters.items() if k != "comment"} or None
        return TableMetadata(
            catalog="hive",
            db=raw_table.dbName,
            table=raw_table.tableName,
            owner=raw_table.owner,
            comment=parameters.get("comment"),
            location=getattr(storage, "location", None),
            tableType=raw_table.tableType or "UNKNOWN",
            columns=normal_columns + partition_columns,
            partitions=None,
            serdeClass=serde_class,
            inputFormat=input_format,
            outputFormat=output_format,
            tblProperties=tbl_properties,
        )

    @staticmethod
    def _to_columns(raw_columns, *, partition_key: bool) -> list[ColumnMetadata]:
        return [
            ColumnMetadata(
                name=item.name,
                dataType=item.type,
                comment=item.comment,
                nullable=True,
                partitionKey=partition_key,
            )
            for item in raw_columns
        ]

    @staticmethod
    def _to_partition_metadata(raw_partition, *, partition_keys: list[str] | None = None) -> PartitionMetadata:
        keys = partition_keys or []
        values = {
            keys[index] if index < len(keys) else f"part_{index}": value
            for index, value in enumerate(raw_partition.values or [])
        }
        location = getattr(getattr(raw_partition, "sd", None), "location", None)
        name = "/".join(f"{key}={value}" for key, value in values.items())
        return PartitionMetadata(name=name, values=values, location=location)

    @staticmethod
    def _parameter_statistics(parameters: dict[str, str]) -> dict[str, Any]:
        mapping = {
            "numRows": "rowCount",
            "totalSize": "totalSizeBytes",
            "rawDataSize": "rawDataSizeBytes",
            "numFiles": "fileCount",
            "COLUMN_STATS_ACCURATE": "columnStatsAccurate",
            "transient_lastDdlTime": "lastAnalyzedEpochSeconds",
        }
        result: dict[str, Any] = {}
        for source_key, target_key in mapping.items():
            value = parameters.get(source_key)
            if value is None:
                continue
            if source_key in {"numRows", "totalSize", "rawDataSize", "numFiles", "transient_lastDdlTime"}:
                try:
                    result[target_key] = int(value)
                except ValueError:
                    result[target_key] = value
            else:
                result[target_key] = value
        return result

    @staticmethod
    def _column_statistics(raw_stats) -> dict[str, Any] | None:
        if raw_stats is None:
            return None
        stats_objects = raw_stats if isinstance(raw_stats, list) else [raw_stats]
        if hasattr(raw_stats, "statsObj"):
            stats_objects = raw_stats.statsObj or []
        if not stats_objects:
            return None
        item = stats_objects[0]
        data = getattr(item, "statsData", None)
        if data is None:
            return None
        payload = None
        stats_type = None
        for field in (
            "longStats", "doubleStats", "stringStats", "binaryStats", "booleanStats",
            "decimalStats", "dateStats", "timestampStats",
        ):
            candidate = getattr(data, field, None)
            if candidate is not None:
                payload = candidate
                stats_type = field.removesuffix("Stats")
                break
        if payload is None:
            return None
        aliases = {
            "numNulls": "nullCount",
            "numDVs": "distinctCount",
            "lowValue": "min",
            "highValue": "max",
            "avgColLen": "averageLength",
            "maxColLen": "maxLength",
            "numTrues": "trueCount",
            "numFalses": "falseCount",
        }
        result: dict[str, Any] = {
            "column": getattr(item, "colName", None),
            "columnType": getattr(item, "colType", None),
            "statisticsType": stats_type,
        }
        for source_key, target_key in aliases.items():
            value = getattr(payload, source_key, None)
            if value is not None:
                result[target_key] = str(value) if source_key in {"lowValue", "highValue"} else value
        last_analyzed = getattr(item, "lastAnalyzed", None)
        if last_analyzed is not None:
            result["lastAnalyzedEpochSeconds"] = last_analyzed
        return result

    @staticmethod
    def _ddl_column_line(column: ColumnMetadata) -> str:
        line = f"  {RealHiveMetastoreAdapter._ddl_identifier(column.name)} {column.data_type}"
        if column.comment:
            line += f" COMMENT '{RealHiveMetastoreAdapter._escape_ddl_string(column.comment)}'"
        return line

    @staticmethod
    def _escape_ddl_string(value: str) -> str:
        return value.replace("'", "\\'")

    @staticmethod
    def _ddl_identifier(value: str) -> str:
        return "`" + value.replace("`", "``") + "`"

    @staticmethod
    def _raise_table_error(exc: Exception, *, db: str, table: str):
        name = type(exc).__name__.lower()
        if "nosuchobject" in name or "notfound" in name:
            raise McpDomainError(
                McpErrorCode.NOT_FOUND,
                "Hive table was not found.",
                details={"db": db, "table": table},
            ) from exc
        raise exc
