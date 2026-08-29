import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, Drawer, Select, Spin, message as antdMessage } from 'antd';
import {
  BugOutlined,
  CodeOutlined,
  FileSearchOutlined,
  MenuOutlined,
  ProfileOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import SessionSidebar from './SessionSidebar';
import MessageList from './MessageList';
import Composer from './Composer';
import {
  answerUserQuestion,
  archiveSession,
  cancelChat,
  decideToolPermission,
  listSessions,
  loadMessages,
  streamChat,
} from '../api/chat';
import type { ChatMessage, SessionVO, SqlCommand, SqlTaskVO, Step, UserQuestionAnswerPayload } from '../types';
import { getTask, getTaskVersion, listTasks, updateTaskVersion } from '../api/tasks';
import { historyMessageForDisplay, isVisibleHistoryMessage } from '../utils/messages';
import { DEFAULT_SQL_COMMAND, parseSqlCommandPrefix } from '../utils/sqlCommand';

const STARTERS: Array<{ command: SqlCommand; title: string; description: string; icon: ReactNode; draft: string }> = [
  {
    command: 'sql_optimize',
    title: 'SQL 优化',
    description: '分析执行瓶颈与资源消耗',
    icon: <RocketOutlined />,
    draft: '请优化当前任务 SQL，优先结合最近一次实际执行分析瓶颈。',
  },
  {
    command: 'sql_generate',
    title: 'SQL 生成',
    description: '根据业务目标生成查询',
    icon: <CodeOutlined />,
    draft: '请根据以下业务需求为当前任务生成 Hive SQL：\n\n',
  },
  {
    command: 'sql_fix',
    title: '错误修复',
    description: '定位报错并给出修复方案',
    icon: <BugOutlined />,
    draft: '请修复当前任务 SQL，并结合以下报错信息分析：\n\n报错：\n',
  },
  {
    command: 'sql_explain',
    title: 'SQL 解读',
    description: '梳理逻辑、数据流和风险',
    icon: <FileSearchOutlined />,
    draft: '请解释当前任务 SQL 的结果粒度、数据流和关键风险。',
  },
];

function createSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }

  // Claude CLI 的 --resume 参数要求 sessionId 必须是标准 UUID。
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function hydrateMessage(message: ChatMessage): ChatMessage {
  if (message.role !== 'assistant' || message.steps?.length) return message;
  const steps: Step[] = [];
  if (message.thinking) steps.push({ kind: 'thinking', text: message.thinking });
  if (message.content) steps.push({ kind: 'text', text: message.content });
  return steps.length ? { ...message, steps } : message;
}

function appendTextStep(message: ChatMessage, kind: 'thinking' | 'text', delta: string) {
  const steps = [...(message.steps ?? [])];
  const last = steps[steps.length - 1];
  if (last?.kind === kind) {
    steps[steps.length - 1] = { ...last, text: last.text + delta };
  } else if (delta) {
    steps.push({ kind, text: delta });
  }

  return {
    ...message,
    steps,
    content: kind === 'text' ? message.content + delta : message.content,
    thinking:
      kind === 'thinking' ? (message.thinking ?? '') + delta : message.thinking,
  };
}

export default function ChatPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { sessionId: routeSessionId } = useParams<{ sessionId: string }>();
  const [sessions, setSessions] = useState<SessionVO[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [draftRequest, setDraftRequest] = useState<{ id: number; text: string }>();
  const [recentOpen, setRecentOpen] = useState(false);
  const [taskOptions, setTaskOptions] = useState<SqlTaskVO[]>([]);
  const [selectedTask, setSelectedTask] = useState<SqlTaskVO>();
  const selectedTaskId = Number(searchParams.get('taskId')) || undefined;
  const selectedExecutionId = Number(searchParams.get('executionId')) || undefined;
  const selectedVersionNo = Number(searchParams.get('versionNo')) || undefined;
  const [sending, setSending] = useState(false);
  const [command, setCommand] = useState<SqlCommand>(DEFAULT_SQL_COMMAND);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const runningSessionIdRef = useRef<string | null>(null);
  const historyRequestIdRef = useRef(0);
  const activeId = routeSessionId ?? null;

  const refreshSessions = useCallback(async () => {
    try {
      setSessions(await listSessions());
    } catch (e) {
      antdMessage.error(`加载会话列表失败：${(e as Error).message}`);
    }
  }, []);

  const searchTasks = useCallback(async (keyword = '') => {
    try {
      const result = await listTasks(keyword, 1, 100);
      setTaskOptions(result.items);
    } catch (error) {
      antdMessage.error(`加载任务失败：${(error as Error).message}`);
    }
  }, []);

  useEffect(() => { void searchTasks(); }, [searchTasks]);
  useEffect(() => {
    if (selectedTaskId || taskOptions.length !== 1) return;
    const next = new URLSearchParams(searchParams);
    next.set('taskId', String(taskOptions[0].id));
    next.delete('executionId');
    setSearchParams(next, { replace: true });
  }, [searchParams, selectedTaskId, setSearchParams, taskOptions]);
  useEffect(() => {
    if (!selectedTaskId) { setSelectedTask(undefined); return; }
    void getTask(selectedTaskId).then(setSelectedTask).catch(() => setSelectedTask(undefined));
  }, [selectedTaskId]);

  useEffect(() => {
    refreshSessions();
  }, [refreshSessions]);

  useEffect(() => () => {
    const sessionId = runningSessionIdRef.current;
    abortControllerRef.current?.abort();
    if (sessionId) void cancelChat(sessionId).catch(() => undefined);
  }, []);

  useEffect(() => {
    const requestId = historyRequestIdRef.current + 1;
    historyRequestIdRef.current = requestId;
    if (!activeId) {
      setMessages([]);
      setHistoryLoading(false);
      return;
    }
    if (runningSessionIdRef.current === activeId) return;

    setMessages([]);
    setHistoryLoading(true);
    void loadMessages(activeId)
      .then((history) => {
        if (historyRequestIdRef.current === requestId) {
          setMessages(
            history
              .filter(isVisibleHistoryMessage)
              .map(historyMessageForDisplay)
              .map((m) => hydrateMessage({ ...m })),
          );
        }
      })
      .catch((e) => {
        if (historyRequestIdRef.current === requestId) {
          antdMessage.error(`加载历史失败：${(e as Error).message}`);
        }
      })
      .finally(() => {
        if (historyRequestIdRef.current === requestId) setHistoryLoading(false);
      });
  }, [activeId]);

  useEffect(() => {
    if (!startedAt) return undefined;
    const timer = window.setInterval(() => {
      setElapsedSeconds(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)));
    }, 500);
    return () => window.clearInterval(timer);
  }, [startedAt]);

  const cleanupRun = useCallback(() => {
    abortControllerRef.current = null;
    runningSessionIdRef.current = null;
    setSending(false);
    setStartedAt(null);
    setElapsedSeconds(0);
  }, []);

  const handleNew = () => {
    if (sending) return;
    historyRequestIdRef.current += 1;
    setRecentOpen(false);
    setMessages([]);
    navigate(`/chat?${searchParams.toString()}`);
  };

  const handleSelect = (sessionId: string) => {
    if (sending) return;
    setRecentOpen(false);
    navigate(`/chat/${sessionId}?${searchParams.toString()}`);
  };

  const handleArchive = async (sessionId: string) => {
    if (sending) return;
    try {
      await archiveSession(sessionId);
      if (sessionId === activeId) {
        setMessages([]);
        navigate('/sessions');
      }
      await refreshSessions();
    } catch (e) {
      antdMessage.error(`归档失败：${(e as Error).message}`);
    }
  };

  const updateRunningAssistant = (
    sessionId: string,
    patch: (m: ChatMessage) => ChatMessage,
  ) => {
    if (runningSessionIdRef.current !== sessionId) return;
    setMessages((prev) => {
      const next = [...prev];
      for (let i = next.length - 1; i >= 0; i--) {
        if (next[i].role === 'assistant') {
          next[i] = patch(next[i]);
          break;
        }
      }
      return next;
    });
  };

  const updatePermissionStatus = (
    requestId: string,
    status: 'allowed' | 'denied' | 'pending',
  ) => {
    setMessages((prev) =>
      prev.map((msg) => {
        if (msg.role !== 'assistant' || !msg.steps) return msg;
        return {
          ...msg,
          steps: msg.steps.map((step) =>
            step.kind === 'permission' && step.requestId === requestId
              ? { ...step, status }
              : step,
          ),
        };
      }),
    );
  };

  const updateUserQuestionStatus = (
    requestId: string,
    patch: Partial<Extract<Step, { kind: 'user_question' }>>,
  ) => {
    setMessages((prev) =>
      prev.map((msg) => {
        if (msg.role !== 'assistant' || !msg.steps) return msg;
        return {
          ...msg,
          steps: msg.steps.map((step) =>
            step.kind === 'user_question' && step.requestId === requestId
              ? { ...step, ...patch }
              : step,
          ),
        };
      }),
    );
  };

  const handlePermissionDecision = async (
    requestId: string,
    decision: 'allow' | 'deny',
  ) => {
    updatePermissionStatus(requestId, decision === 'allow' ? 'allowed' : 'denied');
    try {
      await decideToolPermission(requestId, decision);
    } catch (e) {
      updatePermissionStatus(requestId, 'pending');
      antdMessage.error(`权限决策发送失败：${(e as Error).message}`);
    }
  };

  const handleUserQuestionAnswer = async (
    requestId: string,
    answer: UserQuestionAnswerPayload,
  ) => {
    updateUserQuestionStatus(requestId, {
      status: answer.cancelled ? 'cancelled' : 'answered',
      answers: answer.answers,
    });
    try {
      await answerUserQuestion(requestId, answer);
    } catch (e) {
      updateUserQuestionStatus(requestId, { status: 'pending', answers: undefined });
      antdMessage.error(`澄清问题提交失败：${(e as Error).message}`);
    }
  };

  const handleSend = async (text: string) => {
    if (sending) return;
    const parsed = parseSqlCommandPrefix(text);
    const effectiveCommand = parsed.matchedPrefix ? parsed.command : command;
    const effectiveMessage = parsed.matchedPrefix ? parsed.message : text;
    if (!selectedTaskId) {
      antdMessage.warning('请先选择 SQL 任务');
      return;
    }

    let sessionId = activeId;
    if (!sessionId) {
      sessionId = createSessionId();
      navigate(`/chat/${sessionId}?${searchParams.toString()}`, { replace: true });
    }
    const isFirstTurn = messages.length === 0;

    setMessages((prev) => [
      ...prev,
      { role: 'user', content: effectiveMessage },
      { role: 'assistant', content: '', steps: [], streaming: true },
    ]);

    const controller = new AbortController();
    abortControllerRef.current = controller;
    runningSessionIdRef.current = sessionId;
    setSending(true);
    setElapsedSeconds(0);
    setStartedAt(Date.now());

    try {
      await streamChat(
        { sessionId, taskId: selectedTaskId, executionId: selectedExecutionId, versionNo: selectedVersionNo, command: effectiveCommand, message: effectiveMessage },
        {
          onThinking: (delta) =>
            updateRunningAssistant(sessionId, (m) => appendTextStep(m, 'thinking', delta)),
          onText: (delta) =>
            updateRunningAssistant(sessionId, (m) => appendTextStep(m, 'text', delta)),
          onToolUse: ({ id, name, input }) =>
            updateRunningAssistant(sessionId, (m) => ({
              ...m,
              steps: [...(m.steps ?? []), { kind: 'tool', id, name, input }],
            })),
          onToolResult: ({ toolUseId, content, isError, semanticType }) =>
            updateRunningAssistant(sessionId, (m) => {
              const steps = [...(m.steps ?? [])];
              const index = steps.findIndex(
                (step) => step.kind === 'tool' && step.id === toolUseId,
              );
              if (index === -1) {
                steps.push({
                  kind: 'tool',
                  id: toolUseId,
                  result: content,
                  isError,
                  semanticType,
                });
              } else {
                const step = steps[index];
                if (step.kind === 'tool') {
                  steps[index] = { ...step, result: content, isError, semanticType };
                }
              }
              return { ...m, steps };
            }),
          onPermissionRequest: ({ requestId, toolName, toolInput }) =>
            updateRunningAssistant(sessionId, (m) => ({
              ...m,
              steps: [
                ...(m.steps ?? []),
                { requestId, toolName, toolInput, status: 'pending', kind: 'permission' },
              ],
            })),
          onUserQuestionRequest: ({ requestId, questions, rawInput }) =>
            updateRunningAssistant(sessionId, (m) => ({
              ...m,
              steps: [
                ...(m.steps ?? []),
                { requestId, questions, rawInput, status: 'pending', kind: 'user_question' },
              ],
            })),
          onDone: () => {
            updateRunningAssistant(sessionId, (m) => ({ ...m, streaming: false }));
            cleanupRun();
            if (isFirstTurn) refreshSessions();
          },
          onError: (msg) => {
            updateRunningAssistant(sessionId, (m) => ({
              ...m,
              streaming: false,
              steps: [...(m.steps ?? []), { kind: 'error', text: `出错：${msg}` }],
            }));
            cleanupRun();
          },
        },
        controller.signal,
      );
    } finally {
      if (abortControllerRef.current === controller) {
        cleanupRun();
      }
    }
  };

  const handleStop = async () => {
    const controller = abortControllerRef.current;
    const sessionId = runningSessionIdRef.current;
    controller?.abort();
    if (sessionId) {
      updateRunningAssistant(sessionId, (m) => ({ ...m, streaming: false }));
    }
    cleanupRun();
    if (!sessionId) return;
    try {
      await cancelChat(sessionId);
    } catch (e) {
      antdMessage.warning(`停止请求已在前端生效，后端取消失败：${(e as Error).message}`);
    }
  };

  const currentTitle = sessions.find((session) => session.sessionId === activeId)?.title;

  const applySql = async (sql: string) => {
    if (!selectedTask) return;
    if (!selectedVersionNo) {
      antdMessage.warning('当前选择的是生效代码。请先在任务工作台创建版本，再从对应版本进入 Agent。');
      return;
    }
    try {
      const version = await getTaskVersion(selectedTask.id, selectedVersionNo);
      if (version.status !== 'DRAFT' || !version.canEdit) {
        antdMessage.warning(`版本 v${selectedVersionNo} 当前不可编辑，请选择开发中的版本`);
        return;
      }
      await updateTaskVersion(selectedTask.id, selectedVersionNo, {
        name: version.name,
        description: version.description,
        sql,
        revision: version.revision,
        parameters: version.parameters || [],
      });
      antdMessage.success(`已应用到任务 ${selectedTask.id} 的版本 v${selectedVersionNo}`);
    } catch (error) {
      antdMessage.error(`应用 SQL 失败：${(error as Error).message}`);
    }
  };

  const chooseStarter = (starter: typeof STARTERS[number]) => {
    setCommand(starter.command);
    setDraftRequest({ id: Date.now(), text: starter.draft });
  };

  const recentSidebarProps = {
    sessions,
    activeId,
    onSelect: handleSelect,
    onNew: handleNew,
    onArchive: handleArchive,
    onManage: () => {
      setRecentOpen(false);
      navigate('/sessions');
    },
    disabled: sending,
  };

  const recentSidebar = (
    <SessionSidebar
      {...recentSidebarProps}
    />
  );

  return (
    <div className="chat-layout">
      <div className="desktop-recent-sidebar">{recentSidebar}</div>
      <Drawer
        className="mobile-recent-drawer"
        title="最近会话"
        placement="left"
        width={300}
        open={recentOpen}
        onClose={() => setRecentOpen(false)}
        extra={(
          <Button
            type="text"
            icon={<ProfileOutlined />}
            aria-label="打开会话管理"
            onClick={recentSidebarProps.onManage}
          />
        )}
        styles={{ body: { padding: 0 } }}
      >
        <SessionSidebar {...recentSidebarProps} showHeader={false} />
      </Drawer>
      <div className="chat-main">
        <header className="chat-context-bar">
          <Button
            className="mobile-recent-trigger"
            type="text"
            icon={<MenuOutlined />}
            aria-label="打开最近会话"
            onClick={() => setRecentOpen(true)}
          />
          <div className="chat-context-copy">
            <span className="chat-context-label">SQL Agent</span>
            <span className="chat-context-title">{currentTitle || 'SQL 助手 · 新对话'}</span>
          </div>
          <Select
            className="chat-task-select"
            showSearch
            value={selectedTaskId}
            placeholder="选择 SQL 任务"
            filterOption={false}
            onSearch={(value) => void searchTasks(value)}
            onChange={(value) => {
              const next = new URLSearchParams(searchParams);
              next.set('taskId', String(value)); next.delete('executionId'); setSearchParams(next);
            }}
            options={taskOptions.map((task) => ({ value: task.id, label: `${task.id} · ${task.name}` }))}
          />
        </header>
        {historyLoading ? (
          <div className="chat-loading"><Spin /></div>
        ) : messages.length ? (
          <MessageList
            messages={messages}
            onPermissionDecision={handlePermissionDecision}
            onUserQuestionAnswer={handleUserQuestionAnswer}
            onApplySql={(sql) => void applySql(sql)}
            taskName={selectedTask?.name}
          />
        ) : (
          <div className="chat-welcome">
            <div className="chat-welcome-mark"><CodeOutlined /></div>
            <h1>今天想处理哪段 SQL？</h1>
            <p>选择一个任务开始，或直接在下方输入需求</p>
            <div className="starter-grid">
              {STARTERS.map((starter) => (
                <button key={starter.command} type="button" className="starter-item" onClick={() => chooseStarter(starter)}>
                  <span className={`starter-icon ${starter.command}`}>{starter.icon}</span>
                  <span className="starter-copy">
                    <strong>{starter.title}</strong>
                    <span>{starter.description}</span>
                  </span>
                </button>
              ))}
            </div>
          </div>
        )}
        <Composer
          sendDisabled={!selectedTaskId}
          taskId={selectedTaskId}
          taskOptions={taskOptions.map((task) => ({ value: task.id, label: `${task.id} · ${task.name}` }))}
          running={sending}
          elapsedSeconds={elapsedSeconds}
          command={command}
          onCommandChange={setCommand}
          onTaskSearch={(keyword) => void searchTasks(keyword)}
          onTaskChange={(value) => {
            const next = new URLSearchParams(searchParams);
            next.set('taskId', String(value));
            next.delete('executionId');
            setSearchParams(next, { replace: true });
          }}
          onTaskRequired={() => antdMessage.warning('请先选择 SQL 任务')}
          onSend={handleSend}
          onStop={handleStop}
          draftRequest={draftRequest}
        />
      </div>
    </div>
  );
}
