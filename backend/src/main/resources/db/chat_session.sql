CREATE TABLE IF NOT EXISTS chat_session (
  session_id      VARCHAR(36) NOT NULL COMMENT '前端预生成的 UUID，同时是 Claude session_id',
  ob_id           VARCHAR(20) NOT NULL COMMENT '会话所属登录 obId',
  title           VARCHAR(64) NULL COMMENT '会话标题，取用户首条消息前 30 字符',
  context_type    VARCHAR(40) NULL COMMENT '页面 AI 业务上下文类型',
  context_id      VARCHAR(100) NULL COMMENT '页面 AI 业务实体 ID',
  context_title   VARCHAR(200) NULL COMMENT '页面来源标题',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  last_active_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
  archived        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '归档标记：0未归档、1已归档',
  PRIMARY KEY (session_id),
  KEY idx_chat_session_ob_active (ob_id, archived, last_active_at),
  KEY idx_chat_session_context (ob_id, context_type, context_id, last_active_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL Agent 会话元数据';
