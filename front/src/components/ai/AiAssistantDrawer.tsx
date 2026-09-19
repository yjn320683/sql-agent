import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Drawer, Empty, Input, Space, Tag, Tooltip, message as antdMessage } from 'antd';
import { CloseOutlined, CopyOutlined, RobotOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { diffLines } from 'diff';
import {
  answerUserQuestion,
  cancelChat,
  decideToolPermission,
  loadMessages,
  streamChat,
} from '../../api/chat';
import type {
  AiContext,
  AiIntent,
  AiProposal,
  ProposalActionResult,
  ChatMessage,
  Step,
  UserQuestionAnswerPayload,
} from '../../types';
import MessageList from '../MessageList';
import { historyMessageForDisplay, isVisibleHistoryMessage } from '../../utils/messages';
import { recoverProposals, validateProposal } from './proposalRegistry';

interface Props {
  open: boolean;
  context: AiContext;
  onClose: () => void;
  onApplyProposal?: (proposal: AiProposal) => ProposalActionResult;
}

const INTENT_LABELS: Array<{ value: AiIntent; label: string }> = [
  { value: 'GENERATE', label: '生成' },
  { value: 'OPTIMIZE', label: '优化' },
  { value: 'EXPLAIN', label: '解释' },
  { value: 'DIAGNOSE', label: '诊断' },
  { value: 'RECOMMEND', label: '推荐' },
  { value: 'REVIEW', label: '评审' },
];

const INTENT_PROMPTS: Record<AiIntent, string> = {
  GENERATE: '请基于当前页面的真实数据生成可确认的修改建议。',
  OPTIMIZE: '请分析当前内容并给出可确认的优化 Proposal。',
  FIX: '请诊断当前问题并给出修复 Proposal。',
  EXPLAIN: '请解释当前页面的数据、配置及其影响。',
  REVIEW: '请评审当前页面内容，列出风险与回归建议。',
  DIAGNOSE: '请结合当前页面事实诊断异常、慢点或失败原因。',
  RECOMMEND: '请基于当前页面事实给出参数、依赖或处理顺序建议。',
  COMPARE: '请比较当前页面的基线与候选内容并总结差异。',
  SEARCH: '请根据我的描述查找并定位真实数据对象。',
  SUMMARIZE: '请总结当前页面的关键信息、风险和待办事项。',
};

function createSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function appendTextStep(message: ChatMessage, kind: 'thinking' | 'text', delta: string): ChatMessage {
  const steps = [...(message.steps ?? [])];
  const last = steps[steps.length - 1];
  if (last?.kind === kind) steps[steps.length - 1] = { ...last, text: last.text + delta };
  else if (delta) steps.push({ kind, text: delta });
  return {
    ...message,
    steps,
    content: kind === 'text' ? message.content + delta : message.content,
    thinking: kind === 'thinking' ? (message.thinking ?? '') + delta : message.thinking,
  };
}

function ProposalDiff({ before, after }: { before: string; after: string }) {
  return (
    <div>
      <span>修改差异</span>
      <pre className="ai-proposal-diff">{diffLines(before, after).map((part, index) => (
        <span key={`${index}-${part.value.length}`} className={part.added ? 'diff-added' : part.removed ? 'diff-removed' : ''}>
          {part.value}
        </span>
      ))}</pre>
    </div>
  );
}

export default function AiAssistantDrawer({ open, context, onClose, onApplyProposal }: Props) {
  const contextKey = useMemo(
    () => `${context.contextType}:${context.parentId ?? ''}:${context.entityId ?? 'list'}:${context.versionNo ?? ''}`,
    [context.contextType, context.entityId, context.parentId, context.versionNo],
  );
  const storageKey = `sql-agent.ai-session.${contextKey}`;
  const [sessionId, setSessionId] = useState(() => sessionStorage.getItem(storageKey) ?? createSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [proposals, setProposals] = useState<AiProposal[]>([]);
  const [proposalResults, setProposalResults] = useState<Record<string, ProposalActionResult>>({});
  const [validatingProposal, setValidatingProposal] = useState<string>();
  const [input, setInput] = useState('');
  const [intent, setIntent] = useState<AiIntent>('EXPLAIN');
  const [sending, setSending] = useState(false);
  const [width, setWidth] = useState(() => Number(localStorage.getItem('sql-agent.ai-drawer-width')) || 520);
  const abortRef = useRef<AbortController>();
  const previousContextRef = useRef(contextKey);

  useEffect(() => {
    let active = true;
    if (previousContextRef.current !== contextKey) {
      const wasStreaming = Boolean(abortRef.current);
      abortRef.current?.abort();
      abortRef.current = undefined;
      setSending(false);
      if (wasStreaming) void cancelChat(sessionId).catch(() => undefined);
    }
    previousContextRef.current = contextKey;
    const restored = sessionStorage.getItem(storageKey) ?? createSessionId();
    sessionStorage.setItem(storageKey, restored);
    setSessionId(restored);
    setMessages([]);
    setProposals([]);
    setProposalResults({});
    void loadMessages(restored).then((history) => {
      if (!active) return;
      const visible = history.filter(isVisibleHistoryMessage).map(historyMessageForDisplay);
      setMessages(visible);
      setProposals(recoverProposals(visible));
    }).catch(() => undefined);
    return () => { active = false; };
  }, [contextKey, storageKey]); // sessionId 仅用于终止上一个上下文中的流式请求

  useEffect(() => () => abortRef.current?.abort(), []);

  const updateAssistant = (patch: (message: ChatMessage) => ChatMessage) => {
    setMessages((current) => {
      const next = [...current];
      for (let index = next.length - 1; index >= 0; index -= 1) {
        if (next[index].role === 'assistant') {
          next[index] = patch(next[index]);
          break;
        }
      }
      return next;
    });
  };

  const send = async (value = input) => {
    const text = value.trim();
    if (!text || sending) return;
    setInput('');
    setMessages((current) => [
      ...current,
      { role: 'user', content: text },
      { role: 'assistant', content: '', steps: [], streaming: true },
    ]);
    const controller = new AbortController();
    abortRef.current = controller;
    setSending(true);
    const legacyTaskId = ['OFFLINE_TASK', 'OFFLINE_SCHEDULE'].includes(context.contextType)
      ? Number(context.entityId) || undefined
      : ['OFFLINE_VERSION', 'OFFLINE_EXECUTION'].includes(context.contextType)
        ? Number(context.parentId) || undefined
        : undefined;
    const sqlIntentCommands: Partial<Record<AiIntent, 'sql_generate' | 'sql_optimize' | 'sql_fix' | 'sql_explain' | 'sql_static_check'>> = {
      GENERATE: 'sql_generate', OPTIMIZE: 'sql_optimize', FIX: 'sql_fix', EXPLAIN: 'sql_explain', REVIEW: 'sql_static_check',
    };
    const command = ['OFFLINE_TASK', 'OFFLINE_VERSION'].includes(context.contextType) && typeof context.draft?.sql === 'string'
      ? sqlIntentCommands[intent] ?? 'platform_assist'
      : 'platform_assist';
    try {
      await streamChat(
        {
          sessionId,
          taskId: legacyTaskId,
          executionId: context.contextType === 'OFFLINE_EXECUTION' ? Number(context.entityId) || undefined : undefined,
          versionNo: context.versionNo,
          command,
          context,
          intent,
          message: text,
        },
        {
          onThinking: (delta) => updateAssistant((item) => appendTextStep(item, 'thinking', delta)),
          onText: (delta) => updateAssistant((item) => appendTextStep(item, 'text', delta)),
          onToolUse: ({ id, name, input: toolInput }) => updateAssistant((item) => ({
            ...item, steps: [...(item.steps ?? []), { kind: 'tool', id, name, input: toolInput }],
          })),
          onToolResult: ({ toolUseId, content, isError, semanticType }) => updateAssistant((item) => {
            const steps = [...(item.steps ?? [])];
            const index = steps.findIndex((step) => step.kind === 'tool' && step.id === toolUseId);
            const result: Step = { kind: 'tool', id: toolUseId, result: content, isError, semanticType };
            if (index < 0) steps.push(result);
            else steps[index] = { ...(steps[index] as Extract<Step, { kind: 'tool' }>), ...result };
            return { ...item, steps };
          }),
          onPermissionRequest: ({ requestId, toolName, toolInput }) => updateAssistant((item) => ({
            ...item,
            steps: [...(item.steps ?? []), { kind: 'permission', requestId, toolName, toolInput, status: 'pending' }],
          })),
          onUserQuestionRequest: ({ requestId, questions, rawInput }) => updateAssistant((item) => ({
            ...item,
            steps: [...(item.steps ?? []), { kind: 'user_question', requestId, questions, rawInput, status: 'pending' }],
          })),
          onProposal: (proposal) => setProposals((current) => {
            const identity = proposal.proposalId || `${proposal.target}:${proposal.kind}:${proposal.summary || ''}`;
            return current.some((item) => (item.proposalId || `${item.target}:${item.kind}:${item.summary || ''}`) === identity)
              ? current : [...current, proposal];
          }),
          onDone: () => updateAssistant((item) => ({ ...item, streaming: false })),
          onError: (error) => updateAssistant((item) => ({
            ...item,
            streaming: false,
            steps: [...(item.steps ?? []), { kind: 'error', text: `出错：${error}` }],
          })),
        },
        controller.signal,
      );
    } finally {
      if (abortRef.current === controller) abortRef.current = undefined;
      setSending(false);
    }
  };

  const stop = () => {
    abortRef.current?.abort();
    abortRef.current = undefined;
    setSending(false);
    void cancelChat(sessionId).catch(() => undefined);
    updateAssistant((item) => ({ ...item, streaming: false }));
  };

  const proposalKey = (proposal: AiProposal, index: number) => proposal.proposalId || `${proposal.target}-${index}`;

  const verifyProposal = async (proposal: AiProposal, index: number) => {
    const key = proposalKey(proposal, index);
    setValidatingProposal(key);
    const verified = await validateProposal(proposal, context);
    setProposalResults((current) => ({ ...current, [key]: verified }));
    setValidatingProposal(undefined);
    verified.status === 'APPLIED' ? antdMessage.success(verified.message) : antdMessage.error(verified.message);
    return verified;
  };

  const applyProposal = async (proposal: AiProposal, index: number) => {
    const key = proposalKey(proposal, index);
    const verified = proposalResults[key]?.status === 'APPLIED'
      ? proposalResults[key] : await verifyProposal(proposal, index);
    if (verified.status !== 'APPLIED') return;
    if (!onApplyProposal) {
      const outcome = { status: 'READ_ONLY' as const, message: '当前页面为只读建议；请复制内容或进入对应编辑页面应用' };
      setProposalResults((current) => ({ ...current, [key]: outcome }));
      antdMessage.info(outcome.message);
      return;
    }
    const outcome = onApplyProposal(proposal);
    setProposalResults((current) => ({ ...current, [key]: outcome }));
    outcome.status === 'APPLIED' ? antdMessage.success(outcome.message) : antdMessage.info(outcome.message);
  };

  const copyProposal = async (proposal: AiProposal) => {
    const value = proposal.after ?? (proposal.patch ? JSON.stringify(proposal.patch, null, 2) : proposal.before ?? '');
    if (!value) return;
    try {
      await navigator.clipboard.writeText(value);
      antdMessage.success('Proposal 内容已复制');
    } catch {
      antdMessage.error('复制失败，请手动选择内容');
    }
  };

  const resizeStart = (event: React.PointerEvent<HTMLDivElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const resizeMove = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const next = Math.min(Math.max(window.innerWidth - event.clientX, 420), Math.min(900, window.innerWidth - 80));
    setWidth(next);
    localStorage.setItem('sql-agent.ai-drawer-width', String(next));
  };

  const updatePermission = (requestId: string, decision: 'allow' | 'deny') => {
    setMessages((current) => current.map((item) => ({
      ...item,
      steps: item.steps?.map((step) => step.kind === 'permission' && step.requestId === requestId
        ? { ...step, status: decision === 'allow' ? 'allowed' : 'denied' }
        : step),
    })));
    void decideToolPermission(requestId, decision);
  };

  const answerQuestion = (requestId: string, answer: UserQuestionAnswerPayload) => {
    void answerUserQuestion(requestId, answer);
  };

  return (
    <Drawer
      className="ai-assistant-drawer"
      zIndex={1200}
      width={width}
      open={open}
      onClose={onClose}
      closable={false}
      destroyOnClose={false}
      title={(
        <div className="ai-assistant-title">
          <span><RobotOutlined /> 页面 AI</span>
          <span className="ai-assistant-context">{context.title ?? context.contextType}</span>
          <Button type="text" size="small" icon={<CloseOutlined />} onClick={onClose} aria-label="关闭页面 AI" />
        </div>
      )}
    >
      <div className="ai-drawer-resizer" onPointerDown={resizeStart} onPointerMove={resizeMove} />
      <div className="ai-assistant-body">
        <Space size={[6, 6]} wrap className="ai-intents">
          {INTENT_LABELS.map((item) => (
            <Tag.CheckableTag
              key={item.value}
              checked={intent === item.value}
              onChange={() => { setIntent(item.value); setInput(INTENT_PROMPTS[item.value]); }}
            >{item.label}</Tag.CheckableTag>
          ))}
        </Space>
        <div className="ai-assistant-messages">
          {messages.length ? (
            <MessageList
              messages={messages}
              onPermissionDecision={updatePermission}
              onUserQuestionAnswer={answerQuestion}
              readOnly
            />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="AI 会自动读取当前页面上下文，不会直接执行生产操作" />
          )}
          {proposals.map((proposal, index) => {
            const key = proposalKey(proposal, index); const actionResult = proposalResults[key];
            return (
            <section className="ai-proposal" key={key}>
              <header><strong>{proposal.summary || `${proposal.target} 修改建议`}</strong><Tag>{proposal.kind}</Tag></header>
              {proposal.before !== undefined && proposal.after !== undefined
                ? <ProposalDiff before={proposal.before} after={proposal.after} />
                : proposal.before !== undefined
                  ? <div><span>当前内容</span><pre>{proposal.before || '（空）'}</pre></div>
                  : proposal.after !== undefined
                    ? <div><span>建议内容</span><pre>{proposal.after || '（空）'}</pre></div>
                    : null}
              {proposal.patch && Object.keys(proposal.patch).length > 0 && <pre>{JSON.stringify(proposal.patch, null, 2)}</pre>}
              {!!proposal.risks?.length && <ul>{proposal.risks.map((risk) => <li key={risk}>{risk}</li>)}</ul>}
              {actionResult ? <div className={`ai-proposal-result status-${actionResult.status.toLowerCase()}`}>
                <Tag color={actionResult.status === 'APPLIED' ? 'success' : actionResult.status === 'STALE' ? 'warning' : 'error'}>{actionResult.status}</Tag>
                <span>{actionResult.message}</span>
                {!!actionResult.details?.length && <ul>{actionResult.details.map((detail) => <li key={detail}>{detail}</li>)}</ul>}
              </div> : null}
              <Space size={6}>
                <Button size="small" icon={<CopyOutlined />} onClick={() => void copyProposal(proposal)}>复制</Button>
                <Button size="small" loading={validatingProposal === key} onClick={() => void verifyProposal(proposal, index)}>验证建议</Button>
                <Button type="primary" size="small" disabled={actionResult?.status === 'STALE'} onClick={() => void applyProposal(proposal, index)}>应用到当前页面</Button>
              </Space>
            </section>
          );})}
        </div>
        <div className="ai-assistant-composer">
          <Input.TextArea
            value={input}
            autoSize={{ minRows: 2, maxRows: 6 }}
            placeholder="基于当前页面提问，Enter 发送，Shift+Enter 换行"
            onChange={(event) => setInput(event.target.value)}
            onPressEnter={(event) => {
              if (!event.shiftKey) { event.preventDefault(); void send(); }
            }}
          />
          {sending
            ? <Tooltip title="停止生成"><Button danger icon={<StopOutlined />} onClick={stop} /></Tooltip>
            : <Tooltip title="发送"><Button type="primary" icon={<SendOutlined />} disabled={!input.trim()} onClick={() => void send()} /></Tooltip>}
        </div>
      </div>
    </Drawer>
  );
}
