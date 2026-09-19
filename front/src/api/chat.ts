import type {
  ChatRequest,
  AiProposal,
  DonePayload,
  MessageVO,
  PermissionRequestPayload,
  SessionManageQuery,
  SessionPageVO,
  SessionVO,
  ToolResultPayload,
  ToolUsePayload,
  UserQuestionAnswerPayload,
  UserQuestionRequestPayload,
} from '../types';
import { requestJson } from './client';

const getJson = <T>(url: string): Promise<T> => requestJson<T>(url);

async function postJson<T>(url: string, body?: unknown): Promise<T> {
  return requestJson<T>(url, {
    method: 'POST',
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function patchJson<T>(url: string, body: unknown): Promise<T> {
  return requestJson<T>(url, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export const listSessions = (limit = 20): Promise<SessionVO[]> =>
  getJson<SessionVO[]>(`/api/chat/sessions?limit=${limit}`);

export const manageSessions = (query: SessionManageQuery): Promise<SessionPageVO> => {
  const params = new URLSearchParams({
    status: query.status,
    keyword: query.keyword,
    page: String(query.page),
    pageSize: String(query.pageSize),
    sortBy: query.sortBy,
    sortOrder: query.sortOrder,
  });
  return getJson<SessionPageVO>(`/api/chat/sessions/manage?${params.toString()}`);
};

export const loadMessages = (sessionId: string): Promise<MessageVO[]> =>
  getJson<MessageVO[]>(`/api/chat/sessions/${sessionId}/messages`);

export async function archiveSession(sessionId: string): Promise<void> {
  await postJson<void>(`/api/chat/sessions/${sessionId}/archive`);
}

export async function restoreSession(sessionId: string): Promise<void> {
  await postJson<void>(`/api/chat/sessions/${sessionId}/restore`);
}

export async function renameSession(sessionId: string, title: string): Promise<void> {
  await patchJson<void>(`/api/chat/sessions/${sessionId}`, { title });
}

export async function cancelChat(sessionId: string): Promise<void> {
  await postJson<void>(`/api/chat/sessions/${sessionId}/cancel`);
}

export async function decideToolPermission(
  requestId: string,
  decision: 'allow' | 'deny',
): Promise<void> {
  await postJson<void>(`/api/chat/tool-permissions/${requestId}`, { decision });
}

export async function answerUserQuestion(
  requestId: string,
  answer: UserQuestionAnswerPayload,
): Promise<void> {
  await postJson<void>(`/api/chat/user-questions/${requestId}/answer`, answer);
}

export interface StreamCallbacks {
  onThinking: (delta: string) => void;
  onText: (delta: string) => void;
  onToolUse: (payload: ToolUsePayload) => void;
  onToolResult: (payload: ToolResultPayload) => void;
  onPermissionRequest: (payload: PermissionRequestPayload) => void;
  onUserQuestionRequest: (payload: UserQuestionRequestPayload) => void;
  onProposal?: (payload: AiProposal) => void;
  onDone: (payload: DonePayload) => void;
  onError: (message: string) => void;
}

export async function streamChat(
  req: ChatRequest,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  let resp: Response;
  try {
    resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(req),
      signal,
      credentials: 'include',
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') return;
    callbacks.onError((e as Error).message);
    return;
  }

  if (!resp.ok || !resp.body) {
    callbacks.onError(`HTTP ${resp.status}`);
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let terminalEventReceived = false;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let separator = buffer.match(/\r?\n\r?\n/);
      while (separator?.index !== undefined) {
        const frame = buffer.slice(0, separator.index);
        buffer = buffer.slice(separator.index + separator[0].length);
        const event = dispatchFrame(frame, callbacks);
        terminalEventReceived ||= event === 'done' || event === 'error';
        separator = buffer.match(/\r?\n\r?\n/);
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) {
      const event = dispatchFrame(buffer, callbacks);
      terminalEventReceived ||= event === 'done' || event === 'error';
    }
    if (!terminalEventReceived && !signal?.aborted) {
      callbacks.onError('流连接提前结束，请重试');
    }
  } catch (e) {
    if ((e as Error).name !== 'AbortError') {
      callbacks.onError((e as Error).message);
    }
  }
}

export function dispatchFrame(frame: string, cb: StreamCallbacks): string | undefined {
  let event = 'message';
  const dataLines: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }
  if (dataLines.length === 0) return undefined;

  const raw = dataLines.join('\n');
  let payload: DonePayload &
    Partial<ToolUsePayload> &
    Partial<ToolResultPayload> &
    Partial<PermissionRequestPayload> & {
      questions?: UserQuestionRequestPayload['questions'];
      rawInput?: unknown;
      delta?: string;
      message?: string;
      semanticType?: ToolResultPayload['semanticType'];
      target?: string;
      proposalId?: string;
      kind?: string;
      before?: string;
      after?: string;
      patch?: Record<string, unknown>;
      baseRevision?: number | string;
      summary?: string;
      risks?: string[];
    } = {};
  try {
    payload = JSON.parse(raw);
  } catch {
    payload = { delta: raw };
  }

  switch (event) {
    case 'thinking':
      cb.onThinking(payload.delta ?? '');
      break;
    case 'text':
      cb.onText(payload.delta ?? '');
      break;
    case 'tool_use':
      cb.onToolUse({
        id: payload.id ?? '',
        name: payload.name ?? '',
        input: payload.input,
      });
      break;
    case 'tool_result':
      cb.onToolResult({
        toolUseId: payload.toolUseId ?? '',
        content: payload.content ?? '',
        isError: Boolean(payload.isError),
        semanticType: payload.semanticType,
      });
      break;
    case 'permission_request':
      cb.onPermissionRequest({
        requestId: payload.requestId ?? '',
        toolName: payload.toolName ?? '',
        toolInput: payload.toolInput,
      });
      break;
    case 'user_question_request':
      cb.onUserQuestionRequest({
        requestId: payload.requestId ?? '',
        questions: Array.isArray(payload.questions) ? payload.questions : [],
        rawInput: payload.rawInput,
      });
      break;
    case 'proposal':
      cb.onProposal?.({
        proposalId: payload.proposalId,
        target: payload.target ?? '当前页面',
        kind: payload.kind ?? 'FORM',
        before: payload.before,
        after: payload.after,
        patch: payload.patch,
        baseRevision: payload.baseRevision,
        summary: payload.summary,
        risks: payload.risks,
      });
      break;
    case 'done':
      cb.onDone(payload);
      break;
    case 'error':
      cb.onError(payload.message ?? 'unknown error');
      break;
    default:
      break;
  }
  return event;
}
