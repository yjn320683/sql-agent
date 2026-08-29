import { useEffect, useState } from 'react';
import { Button, Input, Select, Tooltip } from 'antd';
import { ArrowUpOutlined } from '@ant-design/icons';
import type { SqlCommand } from '../types';
import { SQL_COMMAND_LABELS } from '../utils/sqlCommand';

const { TextArea } = Input;

interface Props {
  sendDisabled: boolean;
  taskId?: number;
  taskOptions: Array<{ value: number; label: string }>;
  running: boolean;
  elapsedSeconds: number;
  command: SqlCommand;
  onCommandChange: (command: SqlCommand) => void;
  onTaskChange: (taskId: number) => void;
  onTaskSearch: (keyword: string) => void;
  onTaskRequired: () => void;
  onSend: (text: string) => void;
  onStop: () => void;
  draftRequest?: { id: number; text: string };
}

export default function Composer({
  sendDisabled,
  taskId,
  taskOptions,
  running,
  elapsedSeconds,
  command,
  onCommandChange,
  onTaskChange,
  onTaskSearch,
  onTaskRequired,
  onSend,
  onStop,
  draftRequest,
}: Props) {
  const [value, setValue] = useState('');

  useEffect(() => {
    if (draftRequest) setValue(draftRequest.text);
  }, [draftRequest]);

  const handleSend = () => {
    const text = value.trim();
    if (!text || running) return;
    if (sendDisabled) {
      onTaskRequired();
      return;
    }
    onSend(text);
    setValue('');
  };

  return (
    <div className="composer">
      {running ? (
        <div className="running-indicator" aria-live="polite">
          <span className="running-mark" />
          <span>{elapsedSeconds}s</span>
        </div>
      ) : null}
      <div className="composer-card">
        <TextArea
          className="composer-input"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder="描述对当前任务 SQL 的生成、优化、修复或解释需求"
          autoSize={{ minRows: 2, maxRows: 8 }}
        />
        <div className="composer-toolbar">
          <div className="composer-controls">
            <Select
              className="composer-task-select"
              size="small"
              variant="borderless"
              showSearch
              value={taskId}
              placeholder="选择 SQL 任务"
              filterOption={false}
              onSearch={onTaskSearch}
              onChange={onTaskChange}
              options={taskOptions}
            />
            <Select
              className="command-select"
              size="small"
              variant="borderless"
              value={command}
              onChange={onCommandChange}
              options={Object.entries(SQL_COMMAND_LABELS).map(([value, label]) => ({ value, label }))}
            />
          </div>
          {running ? (
            <Tooltip title="停止请求">
              <Button
                className="composer-action stop-action"
                type="text"
                aria-label="停止请求"
                onClick={onStop}
              >
                <span className="stop-square" />
              </Button>
            </Tooltip>
          ) : (
            <Tooltip title={sendDisabled ? '请先选择 SQL 任务' : '发送'}>
              <Button
                className="composer-action send-action"
                type="primary"
                icon={<ArrowUpOutlined />}
                aria-label="发送"
                onClick={handleSend}
                disabled={!value.trim()}
              />
            </Tooltip>
          )}
        </div>
      </div>
    </div>
  );
}
