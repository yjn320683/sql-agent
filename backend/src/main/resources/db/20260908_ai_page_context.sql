-- 页面内 AI 会话来源。执行前请先确认目标库已包含 chat_session。
ALTER TABLE chat_session
  ADD COLUMN context_type VARCHAR(40) NULL COMMENT '页面 AI 业务上下文类型' AFTER title,
  ADD COLUMN context_id VARCHAR(100) NULL COMMENT '页面 AI 业务实体 ID' AFTER context_type,
  ADD COLUMN context_title VARCHAR(200) NULL COMMENT '页面来源标题' AFTER context_id,
  ADD KEY idx_chat_session_context (ob_id, context_type, context_id, last_active_at);
