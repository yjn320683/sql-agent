from app.models import UserQuestionAnswer


def test_user_question_answer_model_accepts_null_cancelled() -> None:
    answer = UserQuestionAnswer.model_validate({"answers": [], "cancelled": None, "reason": None})

    assert answer.cancelled is None
    assert answer.answers == []
