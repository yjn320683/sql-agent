"""聊天 SSE 路由。"""

from fastapi import APIRouter
from sse_starlette.sse import EventSourceResponse

from app.claude.runner import cancel_chat, run_chat
from app.models import CancelRequest, ChatRequest

router = APIRouter()


@router.post("/chat/stream")
async def chat_stream(req: ChatRequest) -> EventSourceResponse:
    """接收会话、任务、SQL 命令和用户需求，以 SSE 流式返回对话事件。"""
    return EventSourceResponse(
        run_chat(
            req.session_id,
            req.ob_id,
            req.task_id,
            req.message,
            command=req.command,
            execution_id=req.execution_id,
            version_no=req.version_no,
            context=req.context.model_dump(by_alias=True) if req.context else None,
            intent=req.intent,
        )
    )


@router.post("/chat/cancel")
async def chat_cancel(req: CancelRequest) -> dict[str, bool]:
    """取消当前 session 正在运行的请求。"""
    return {"accepted": cancel_chat(req.session_id)}
