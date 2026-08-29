"""工具权限决策路由。"""

from fastapi import APIRouter

from app.models import PermissionDecision
from app.permissions.manager import resolve_permission

router = APIRouter()


@router.post("/permissions/{request_id}")
def decide_permission(request_id: str, req: PermissionDecision) -> dict[str, bool]:
    """接收 Web 回传的工具权限决策。"""
    accepted = resolve_permission(request_id, req.decision)
    return {"accepted": accepted}
