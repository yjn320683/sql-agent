import type { ChatMessage } from '../types';

const AGENT_PROMPT_PREFIX = '当前 command：';
const USER_REQUIREMENT_MARKER = '\n用户需求：\n';

export function historyMessageForDisplay(message: ChatMessage): ChatMessage {
  if (message.role !== 'user') return message;
  const content = message.content;
  if (
    !content.startsWith(AGENT_PROMPT_PREFIX)
    || !content.includes('\n当前 taskId：')
    || !content.includes('\n当前 executionId：')
  ) {
    return message;
  }
  const markerIndex = content.indexOf(USER_REQUIREMENT_MARKER);
  if (markerIndex < 0) return message;
  return {
    ...message,
    content: content.slice(markerIndex + USER_REQUIREMENT_MARKER.length),
  };
}

export function isVisibleHistoryMessage(message: ChatMessage): boolean {
  if (message.role !== 'user') return true;
  return !message.content.trimStart().startsWith('Base directory for this skill:');
}
