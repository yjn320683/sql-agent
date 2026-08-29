"""FastAPI 入口：健康检查、SSE 聊天和工具权限确认。"""

import sys
import logging
from contextlib import asynccontextmanager
from pathlib import Path

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI

from app.api import register_routes
from app.core.tracing import configure_tracing
from app.settings import get_settings

LOGGER = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时加载 .env / cwd 配置（触发缓存）。"""
    logging.getLogger("pyhive").setLevel(logging.WARNING)
    settings = get_settings()
    configure_tracing(settings)
    manager = None
    if settings.sql_agent_db_uri and settings.hive_server2_uri:
        try:
            from app.execution.manager import get_execution_manager
            manager = get_execution_manager()
            await manager.recover()
        except Exception as exc:  # noqa: BLE001 - 未执行任务 DDL 时不阻断聊天服务
            LOGGER.warning("任务执行器恢复失败 errorType=%s", type(exc).__name__)
            manager = None
    yield
    if manager is not None:
        await manager.shutdown()


def create_app() -> FastAPI:
    """创建 FastAPI app 并注册路由。"""
    app = FastAPI(title="sql-agent", lifespan=lifespan)
    register_routes(app)
    return app


app = create_app()


if __name__ == "__main__":
    # 兼容 IDEA 直接运行本文件，并将其余参数交给 uvicorn CLI 解析。
    import uvicorn

    uvicorn.main()
