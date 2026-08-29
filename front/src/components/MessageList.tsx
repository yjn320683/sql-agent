import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Checkbox, Collapse, Input, Radio, Spin, Tag } from 'antd';
import type {
  ChatMessage,
  Step,
  UserQuestionAnswerItem,
  UserQuestionAnswerPayload,
  UserQuestionItem,
} from '../types';
import MarkdownContent from './MarkdownContent';

interface Props {
  messages: ChatMessage[];
  onPermissionDecision: (requestId: string, decision: 'allow' | 'deny') => void;
  onUserQuestionAnswer: (requestId: string, answer: UserQuestionAnswerPayload) => void;
  onApplySql?: (sql: string) => void;
  taskName?: string;
  readOnly?: boolean;
}

const TOOL_LABELS: Record<string, string> = {
  mcp__sql_agent__hive_list_databases: '查询 Hive 数据库',
  mcp__sql_agent__hive_search_tables: '搜索 Hive 表',
  mcp__sql_agent__hive_get_table: '查询表元数据',
  mcp__sql_agent__hive_get_columns: '查询字段',
  mcp__sql_agent__hive_get_partitions: '查询分区',
  mcp__sql_agent__hive_get_table_ddl: '查询建表语句',
  mcp__sql_agent__hive_find_column_usage: '字段反查表',
  mcp__sql_agent__data_map_get_table_primary_keys: '查询表主键',
};

export default function MessageList({
  messages,
  onPermissionDecision,
  onUserQuestionAnswer,
  onApplySql,
  taskName,
  readOnly = false,
}: Props) {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="message-list">
      {messages.map((msg, idx) => (
        <div
          key={idx}
          className={msg.role === 'user' ? 'msg-row user' : 'msg-row assistant'}
        >
          <div className={`bubble ${msg.role}`}>
            {msg.role === 'user'
              ? msg.content
              : renderAssistant(msg, onPermissionDecision, onUserQuestionAnswer, onApplySql, taskName, readOnly)}
          </div>
        </div>
      ))}
      <div ref={endRef} />
    </div>
  );
}

function renderAssistant(
  msg: ChatMessage,
  onPermissionDecision: Props['onPermissionDecision'],
  onUserQuestionAnswer: Props['onUserQuestionAnswer'],
  onApplySql?: Props['onApplySql'],
  taskName?: string,
  readOnly = false,
) {
  const steps = normalizeSteps(msg);
  if (steps.length === 0) {
    return msg.streaming ? <Spin size="small" /> : null;
  }

  return (
    <div className="assistant-steps">
      {groupSteps(steps).map((group, index) =>
        renderGroup(group, index, msg, onPermissionDecision, onUserQuestionAnswer, onApplySql, taskName, readOnly),
      )}
      {msg.streaming && !hasVisibleText(steps) ? <Spin size="small" /> : null}
    </div>
  );
}

function normalizeSteps(msg: ChatMessage): Step[] {
  if (msg.steps?.length) return msg.steps;
  const steps: Step[] = [];
  if (msg.thinking) steps.push({ kind: 'thinking', text: msg.thinking });
  if (msg.content) steps.push({ kind: 'text', text: msg.content });
  return steps;
}

function renderStep(
  step: Step,
  index: number,
  msg: ChatMessage,
  onPermissionDecision: Props['onPermissionDecision'],
  onUserQuestionAnswer: Props['onUserQuestionAnswer'],
  onApplySql?: Props['onApplySql'],
  taskName?: string,
  readOnly = false,
) {
  if (step.kind === 'thinking') return null;

  if (step.kind === 'text') {
    return (
      <div key={index} className="content">
        <MarkdownContent text={step.text} streaming={msg.streaming && isLastTextStep(msg, index)} onApplySql={onApplySql} taskName={taskName} />
      </div>
    );
  }

  if (step.kind === 'error') {
    return (
      <div key={index} className="error-step">
        {step.text}
      </div>
    );
  }

  if (step.kind === 'permission') {
    return (
      <div key={index} className="permission-step">
        <div className="step-title">
          <span>需要确认工具调用</span>
          <Tag color={permissionColor(step.status)}>{permissionLabel(step.status)}</Tag>
        </div>
        <div className="tool-summary">
          {step.toolName} · {summarizeInput(step.toolInput)}
        </div>
        {step.status === 'pending' && !readOnly ? (
          <div className="permission-actions">
            <Button
              size="small"
              type="primary"
              onClick={() => onPermissionDecision(step.requestId, 'allow')}
            >
              允许
            </Button>
            <Button
              size="small"
              danger
              onClick={() => onPermissionDecision(step.requestId, 'deny')}
            >
              拒绝
            </Button>
          </div>
        ) : null}
      </div>
    );
  }

  if (step.kind === 'user_question') {
    return (
      <UserQuestionStep
        key={step.requestId || index}
        step={step}
        onAnswer={onUserQuestionAnswer}
        readOnly={readOnly}
      />
    );
  }

  return (
    <ToolDetails key={index} step={step} />
  );
}

type VisibleStep = Exclude<Step, { kind: 'thinking' }>;
type ToolStep = Extract<Step, { kind: 'tool' }>;
type StepGroup = VisibleStep | { kind: 'tool-group'; tools: ToolStep[] };

function groupSteps(steps: Step[]): StepGroup[] {
  const groups: StepGroup[] = [];
  for (const step of steps) {
    if (step.kind === 'thinking') continue;
    if (step.kind !== 'tool') {
      groups.push(step);
      continue;
    }
    const last = groups[groups.length - 1];
    if (last && typeof last === 'object' && last.kind === 'tool-group') {
      last.tools.push(step);
    } else {
      groups.push({ kind: 'tool-group', tools: [step] });
    }
  }
  return groups;
}

function renderGroup(
  group: StepGroup,
  index: number,
  msg: ChatMessage,
  onPermissionDecision: Props['onPermissionDecision'],
  onUserQuestionAnswer: Props['onUserQuestionAnswer'],
  onApplySql?: Props['onApplySql'],
  taskName?: string,
  readOnly = false,
) {
  if (typeof group === 'object' && group.kind === 'tool-group') {
    return <ToolGroup key={index} tools={group.tools} />;
  }
  return renderStep(group, index, msg, onPermissionDecision, onUserQuestionAnswer, onApplySql, taskName, readOnly);
}

type UserQuestionStepType = Extract<Step, { kind: 'user_question' }>;
type QuestionValue = {
  selectedLabels: string[];
  customAnswer: string;
};

function UserQuestionStep({
  step,
  onAnswer,
  readOnly,
}: {
  step: UserQuestionStepType;
  onAnswer: Props['onUserQuestionAnswer'];
  readOnly: boolean;
}) {
  const initialValues = useMemo(() => valuesFromAnswers(step.answers), [step.answers]);
  const [values, setValues] = useState<Record<number, QuestionValue>>(initialValues);
  const answered = step.status === 'answered';
  const cancelled = step.status === 'cancelled';
  const pending = step.status === 'pending' && !readOnly;
  const answers = useMemo(() => buildQuestionAnswers(step.questions, values), [step.questions, values]);
  const canSubmit = pending && isQuestionAnswered(step.questions, values);

  return (
    <div className="user-question-step">
      <div className="step-title">
        <span>需要你确认几个业务问题</span>
        <Tag color={answered ? 'green' : cancelled ? 'red' : 'blue'}>
          {answered ? '已提交' : cancelled ? '已取消' : '待回答'}
        </Tag>
      </div>
      <div className="user-question-list">
        {step.questions.map((question, index) => (
          <QuestionCard
            key={`${step.requestId}-${index}`}
            question={question}
            index={index}
            value={values[index]}
            disabled={!pending}
            onChange={(value) => setValues((prev) => ({ ...prev, [index]: value }))}
          />
        ))}
      </div>
      {answered && step.answers?.length ? (
        <div className="user-question-summary">
          {step.answers.map((answer, index) => (
            <div key={`${answer.question}-${index}`}>
              {answer.question}：{answerSummary(answer)}
            </div>
          ))}
        </div>
      ) : null}
      {pending ? (
        <div className="permission-actions">
          <Button
            size="small"
            type="primary"
            disabled={!canSubmit}
            onClick={() => onAnswer(step.requestId, { answers })}
          >
            提交选择
          </Button>
          <Button
            size="small"
            onClick={() =>
              onAnswer(step.requestId, { cancelled: true, reason: '用户取消了澄清问题' })
            }
          >
            取消
          </Button>
        </div>
      ) : null}
    </div>
  );
}

function QuestionCard({
  question,
  index,
  value,
  disabled,
  onChange,
}: {
  question: UserQuestionItem;
  index: number;
  value?: QuestionValue;
  disabled: boolean;
  onChange: (value: QuestionValue) => void;
}) {
  const options = question.options ?? [];
  const title = question.header || `问题 ${index + 1}`;
  const currentValue: QuestionValue = value ?? { selectedLabels: [], customAnswer: '' };
  const customPlaceholder = options.length ? '其它 / 自定义输入' : '请输入你的答案';

  return (
    <div className="user-question-card">
      <div className="user-question-header">{title}</div>
      <div className="user-question-title">{question.question}</div>
      {options.length === 0 ? null : question.multiSelect ? (
        <Checkbox.Group
          className="user-question-options"
          value={currentValue.selectedLabels}
          disabled={disabled}
          onChange={(checked) =>
            onChange({ ...currentValue, selectedLabels: checked.map(String) })
          }
        >
          {options.map((option) => (
            <Checkbox key={option.label} value={option.label} className="user-question-option">
              <span className="user-question-option-label">{option.label}</span>
              {option.description ? (
                <span className="user-question-option-desc">{option.description}</span>
              ) : null}
            </Checkbox>
          ))}
        </Checkbox.Group>
      ) : (
        <Radio.Group
          className="user-question-options"
          value={currentValue.selectedLabels[0]}
          disabled={disabled}
          onChange={(event) =>
            onChange({ ...currentValue, selectedLabels: [event.target.value] })
          }
        >
          {options.map((option) => (
            <Radio key={option.label} value={option.label} className="user-question-option">
              <span className="user-question-option-label">{option.label}</span>
              {option.description ? (
                <span className="user-question-option-desc">{option.description}</span>
              ) : null}
            </Radio>
          ))}
        </Radio.Group>
      )}
      <Input.TextArea
        className="user-question-custom-input"
        value={currentValue.customAnswer}
        disabled={disabled}
        autoSize={{ minRows: 2, maxRows: 5 }}
        placeholder={customPlaceholder}
        onChange={(event) =>
          onChange({ ...currentValue, customAnswer: event.target.value })
        }
      />
    </div>
  );
}

function buildQuestionAnswers(
  questions: UserQuestionItem[],
  values: Record<number, QuestionValue>,
): UserQuestionAnswerItem[] {
  return questions.map((question, index) => {
    const value = values[index];
    const selectedLabels = value?.selectedLabels ?? [];
    const customAnswer = value?.customAnswer.trim();
    const answer: UserQuestionAnswerItem = {
      question: question.question,
      header: question.header,
      selectedLabels,
      selectedOptions: (question.options ?? []).filter((option) =>
        selectedLabels.includes(option.label),
      ),
    };
    if (customAnswer) answer.customAnswer = customAnswer;
    return answer;
  });
}

function valuesFromAnswers(
  answers?: UserQuestionAnswerItem[],
): Record<number, QuestionValue> {
  const values: Record<number, QuestionValue> = {};
  answers?.forEach((answer, index) => {
    values[index] = {
      selectedLabels: answer.selectedLabels ?? [],
      customAnswer: answer.customAnswer ?? '',
    };
  });
  return values;
}

function isQuestionAnswered(
  questions: UserQuestionItem[],
  values: Record<number, QuestionValue>,
): boolean {
  return questions.every((_, index) => {
    const value = values[index];
    return Boolean(value?.selectedLabels.length || value?.customAnswer.trim());
  });
}

function answerSummary(answer: UserQuestionAnswerItem): string {
  const parts = [...(answer.selectedLabels ?? [])];
  if (answer.customAnswer?.trim()) parts.push(answer.customAnswer.trim());
  return parts.join('、') || '未选择';
}

function ToolGroup({ tools }: { tools: ToolStep[] }) {
  const hasError = tools.some((tool) => tool.isError && !isAskUserQuestionTool(tool));
  const pending = tools.some((tool) => tool.result === undefined);
  const label =
    tools.length === 1
      ? toolLabel(tools[0])
      : `Used ${tools.length} tools${pending ? ' · running' : ''}`;

  return (
    <Collapse
      ghost
      size="small"
      className={`tool-group${hasError ? ' has-warning' : ''}`}
      items={[
        {
          key: 'tools',
          label,
          children: (
            <div className="tool-group-body">
              {tools.map((tool, index) => (
                <ToolDetails key={tool.id || index} step={tool} />
              ))}
            </div>
          ),
        },
      ]}
    />
  );
}

function ToolDetails({ step }: { step: ToolStep }) {
  if (isAskUserQuestionTool(step)) {
    return (
      <div className="tool-detail question-tool-detail">
        <div className="step-title">{questionToolLabel(step)}</div>
        <div className="tool-result-label">已通过前端澄清问题继续对话</div>
      </div>
    );
  }

  const resultLabel = step.isError ? '工具返回提示' : '工具结果';

  return (
    <div className={`tool-detail${step.isError ? ' tool-warning' : ''}`}>
      <div className="step-title">{toolLabel(step)}</div>
      {step.input !== undefined ? (
        <pre className="tool-code">{formatValue(step.input)}</pre>
      ) : null}
      {step.result === undefined ? (
        <div className="tool-pending">等待工具结果...</div>
      ) : (
        <div className="tool-result-block">
          <div className="tool-result-label">{resultLabel}</div>
          <pre className="tool-result">{step.result}</pre>
        </div>
      )}
    </div>
  );
}

function isAskUserQuestionTool(step: ToolStep): boolean {
  return step.name === 'AskUserQuestion' ||
    step.semanticType === 'user_question_answer' ||
    step.semanticType === 'user_question_prompt';
}

function toolLabel(step: ToolStep): string {
  if (isAskUserQuestionTool(step)) return questionToolLabel(step);
  const name = step.name ? TOOL_LABELS[step.name] ?? step.name : '工具调用';
  const summary = summarizeInput(step.input);
  return summary ? `${name} · ${oneLine(summary)}` : name;
}

function questionToolLabel(step: ToolStep): string {
  const summary = summarizeQuestionHeaders(step.input);
  return summary ? `AskUserQuestion · ${summary}` : 'AskUserQuestion';
}

function summarizeQuestionHeaders(input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const questions = (input as { questions?: unknown }).questions;
  if (!Array.isArray(questions)) return '';
  const labels = questions
    .map((question) => {
      if (!question || typeof question !== 'object') return '';
      const record = question as Record<string, unknown>;
      return stringify(record.header || record.question);
    })
    .filter(Boolean);
  return oneLine(labels.join(' / '));
}

function isLastTextStep(msg: ChatMessage, index: number): boolean {
  const steps = normalizeSteps(msg);
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i].kind === 'text') return i === index;
  }
  return false;
}

function hasVisibleText(steps: Step[]): boolean {
  return steps.some((step) => step.kind === 'text' && step.text.length > 0);
}

function permissionColor(status: 'pending' | 'allowed' | 'denied') {
  if (status === 'allowed') return 'green';
  if (status === 'denied') return 'red';
  return 'gold';
}

function permissionLabel(status: 'pending' | 'allowed' | 'denied') {
  if (status === 'allowed') return '已允许';
  if (status === 'denied') return '已拒绝';
  return '待确认';
}

function summarizeInput(input: unknown): string {
  if (!input || typeof input !== 'object') return stringify(input);
  const record = input as Record<string, unknown>;
  const key =
    record.command ??
    record.file_path ??
    record.path ??
    record.pattern ??
    record.description;
  return key ? stringify(key) : stringify(input);
}

function oneLine(value: string): string {
  const line = value.replace(/\s+/g, ' ').trim();
  return line.length > 96 ? `${line.slice(0, 93)}...` : line;
}

function formatValue(value: unknown): string {
  if (typeof value === 'string') return value;
  return stringify(value);
}

function stringify(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
