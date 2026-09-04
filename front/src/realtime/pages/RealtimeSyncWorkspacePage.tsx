import { ProfileOutlined } from '@ant-design/icons';
import { Tabs } from 'antd';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import RealtimeSyncTaskEditorPage from './RealtimeSyncTaskEditorPage';
import RealtimeSyncTasksPage from './RealtimeSyncTasksPage';

interface WorkspaceLocationState {
  returnTo?: string;
  taskName?: string;
}

export default function RealtimeSyncWorkspacePage() {
  const { taskId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const creating = location.pathname.endsWith('/new');
  const editing = Boolean(taskId);
  const activeKey = creating ? 'new' : editing ? `task:${taskId}` : 'list';
  const state = location.state as WorkspaceLocationState | null;
  const returnTo = state?.returnTo || '/realtime/sync-tasks';
  const editorTitle = creating ? '新建同步任务' : state?.taskName || `同步任务 ${taskId}`;

  const items = [
    {
      key: 'list',
      label: <span className="task-workspace-tab-label"><ProfileOutlined />任务列表</span>,
      closable: false,
      children: activeKey === 'list' ? <RealtimeSyncTasksPage workspace /> : null,
    },
    ...(activeKey !== 'list' ? [{
      key: activeKey,
      label: <span className="task-workspace-tab-label">{editorTitle}</span>,
      closable: true,
      children: <RealtimeSyncTaskEditorPage />,
    }] : []),
  ];

  return (
    <div className="task-workspace-page realtime-sync-workspace-page">
      <Tabs
        className="task-workspace-tabs realtime-sync-workspace-tabs ui-flat-tabs"
        type="editable-card"
        hideAdd
        activeKey={activeKey}
        items={items}
        onChange={(key) => {
          if (key === 'list') navigate(returnTo);
        }}
        onEdit={(_targetKey, action) => {
          if (action === 'remove') navigate(returnTo);
        }}
      />
    </div>
  );
}
