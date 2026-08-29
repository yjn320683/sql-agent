"""Backend 控制 Hive SQL 执行实例的内部 API。"""

from fastapi import APIRouter, HTTPException, status

from app.execution.manager import get_execution_manager

router = APIRouter(prefix="/sql-executions")


@router.post("/{execution_id}/start")
async def start_execution(execution_id: int) -> dict[str, bool]:
    try:
        accepted = await get_execution_manager().start(execution_id)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Task executor unavailable") from exc
    return {"accepted": accepted}


@router.post("/{execution_id}/cancel")
async def cancel_execution(execution_id: int) -> dict[str, bool]:
    try:
        accepted = await get_execution_manager().cancel(execution_id)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Task executor unavailable") from exc
    return {"accepted": accepted}
