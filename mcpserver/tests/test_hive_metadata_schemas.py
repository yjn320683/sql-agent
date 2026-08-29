from __future__ import annotations

import pytest
from pydantic import ValidationError

from sql_agent_mcp_server.domains.hive_metadata.schemas import SearchTablesRequest


def test_search_tables_request_defaults() -> None:
    request = SearchTablesRequest()

    assert request.catalog == "hive"
    assert request.limit == 20
    assert request.offset == 0
    assert request.include_columns is False
    assert request.include_partitions is False


def test_search_tables_request_rejects_invalid_limit() -> None:
    with pytest.raises(ValidationError):
        SearchTablesRequest(limit=0)

    with pytest.raises(ValidationError):
        SearchTablesRequest(limit=101)
