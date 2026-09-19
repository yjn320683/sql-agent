import { useEffect, useState } from 'react';
import { Button, Space, Typography, message } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { getTask } from '../../api/tasks';
import type { SqlTaskVO } from '../../types';
import TaskExecutionPanel from './TaskExecutionPanel';

export default function TaskExecutionListPage() {
  const navigate = useNavigate();
  const taskId = Number(useParams<{ taskId: string }>().taskId);
  const [task, setTask] = useState<SqlTaskVO>();
  useEffect(() => {
    void getTask(taskId)
      .then(setTask)
      .catch((error) => message.error(`加载任务失败：${(error as Error).message}`));
  }, [taskId]);

  return <div className="page-content data-page execution-list-page">
    <header className="data-page-header">
      <Space><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')} /><div><Typography.Title level={2}>{task?.name || '任务实例'}</Typography.Title><Typography.Text type="secondary">任务 {taskId}</Typography.Text></div></Space>
    </header>
    <section className="data-panel"><TaskExecutionPanel taskId={taskId} task={task} /></section>
  </div>;
}
