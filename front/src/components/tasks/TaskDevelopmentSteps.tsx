import type { ReactNode } from 'react';
import { Steps, Tooltip } from 'antd';
import { LeftOutlined, RightOutlined } from '@ant-design/icons';

export const taskStepsCollapsedKey = 'sql-agent-task-steps-collapsed';

export interface TaskDevelopmentStepItem {
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
}

interface Props {
  className?: string;
  collapsed: boolean;
  current: number;
  items: TaskDevelopmentStepItem[];
  onChange: (current: number) => void;
  onCollapsedChange: (collapsed: boolean) => void;
}

export default function TaskDevelopmentSteps({ className, collapsed, current, items, onChange, onCollapsedChange }: Props) {
  const toggle = () => {
    const next = !collapsed;
    window.localStorage.setItem(taskStepsCollapsedKey, String(next));
    onCollapsedChange(next);
  };

  return (
    <aside className={['task-development-steps', className, collapsed ? 'collapsed' : ''].filter(Boolean).join(' ')}>
      <Tooltip title={collapsed ? '展开步骤导航' : '收起步骤导航'} placement="right">
        <button
          type="button"
          className="task-development-steps-toggle"
          aria-label={collapsed ? '展开步骤导航' : '收起步骤导航'}
          aria-expanded={!collapsed}
          onClick={toggle}
        >
          {collapsed ? <RightOutlined /> : <LeftOutlined />}
        </button>
      </Tooltip>
      <Steps direction="vertical" current={current} items={items} onChange={onChange} />
    </aside>
  );
}
