import { Alert, Button, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import SyncTaskEditorDrawer from '../components/SyncTaskEditorDrawer';
import { getSyncTask } from '../api';
import type { SyncTask } from '../types';

interface EditorLocationState {
  returnTo?: string;
}

export default function RealtimeSyncTaskEditorPage() {
  const { taskId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const [task, setTask] = useState<SyncTask>();
  const [loading, setLoading] = useState(Boolean(taskId));
  const [error, setError] = useState('');
  const returnTo = (location.state as EditorLocationState | null)?.returnTo || '/realtime/sync-tasks';

  useEffect(() => {
    if (!taskId) {
      setTask(undefined);
      setLoading(false);
      setError('');
      return;
    }
    const id = Number(taskId);
    if (!Number.isInteger(id) || id <= 0) {
      setError('同步任务 ID 无效');
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError('');
    void getSyncTask(id).then((value) => {
      if (active) setTask(value);
    }).catch((reason) => {
      if (active) setError((reason as Error).message);
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [taskId]);

  if (loading) return <div className="realtime-sync-editor-route-state"><Spin size="large" /></div>;
  if (error) {
    return (
      <div className="realtime-sync-editor-route-state">
        <Alert type="error" showIcon message="同步任务加载失败" description={error} action={<Button onClick={() => navigate(returnTo)}>返回任务列表</Button>} />
      </div>
    );
  }

  return (
    <SyncTaskEditorDrawer
      open
      task={task}
      onClose={() => navigate(returnTo)}
      onSaved={() => navigate(returnTo, { replace: true })}
    />
  );
}
