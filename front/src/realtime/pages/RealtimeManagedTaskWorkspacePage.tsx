import { ProfileOutlined } from '@ant-design/icons';
import { Tabs } from 'antd';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import type { ManagedTaskType } from '../types';
import RealtimeManagedTaskEditorPage from './RealtimeManagedTaskEditorPage';
import RealtimeManagedTasksPage from './RealtimeManagedTasksPage';

export default function RealtimeManagedTaskWorkspacePage({ taskType }: { taskType: ManagedTaskType }) {
  const { taskId } = useParams(); const location = useLocation(); const navigate = useNavigate(); const creating = location.pathname.endsWith('/new'); const viewing = location.pathname.endsWith('/view'); const activeKey = creating ? 'new' : taskId ? `${viewing ? 'view' : 'task'}:${taskId}` : 'list'; const label = taskType === 'compute' ? '计算' : '出仓'; const state = location.state as { returnTo?: string; taskName?: string } | null; const returnTo = state?.returnTo || `/realtime/${taskType}`;
  const editorTitle = creating ? `新建${label}任务` : viewing ? `查看 · ${state?.taskName || `${label}任务 ${taskId}`}` : state?.taskName || `编辑${label}任务 ${taskId}`;
  return <div className="task-workspace-page realtime-sync-workspace-page"><Tabs className="task-workspace-tabs realtime-sync-workspace-tabs ui-flat-tabs" type="editable-card" hideAdd activeKey={activeKey} items={[{ key: 'list', label: <span className="task-workspace-tab-label"><ProfileOutlined />任务列表</span>, closable: false, children: activeKey === 'list' ? <RealtimeManagedTasksPage taskType={taskType} workspace /> : null }, ...(activeKey === 'list' ? [] : [{ key: activeKey, label: editorTitle, closable: true, children: <RealtimeManagedTaskEditorPage taskType={taskType} /> }])]} onChange={(key) => { if (key === 'list') navigate(returnTo); }} onEdit={(_, action) => { if (action === 'remove') navigate(returnTo); }} /></div>;
}
