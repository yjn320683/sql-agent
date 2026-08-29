"""业务澄清问题回答路由。"""

from fastapi import APIRouter

from app.models import UserQuestionAnswer
from app.user_questions.manager import resolve_user_question

router = APIRouter()


@router.post("/user-questions/{request_id}/answer")
def answer_user_question(request_id: str, req: UserQuestionAnswer) -> dict[str, bool]:
    """接收 Web 回传的业务澄清问题答案。"""
    accepted = resolve_user_question(request_id, req.model_dump())
    return {"accepted": accepted}
