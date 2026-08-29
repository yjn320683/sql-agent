import { useCallback, useEffect, useMemo, useState } from 'react';
import { Modal, Tabs, message } from 'antd';
import { ProfileOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { getTask } from '../../api/tasks';
import type { SqlTaskVO } from '../../types';
import TaskEditorPage from './TaskEditorPage';
import TaskListPage from './TaskListPage';
import { parseStoredTaskIds, parseTaskWorkspaceRoute } from './taskWorkspaceState';

interface TaskTab {
  taskId: number;
  title: string;
  versionNo?: number;
}

const STORAGE_KEY = 'sql-agent.open-task-tabs.v1';

export default function TaskWorkspacePage() {
  const location = useLocation();
  const navigate = useNavigate();
  const route = useMemo(() => parseTaskWorkspaceRoute(location.pathname), [location.pathname]);
  const routeVersionNo = useMemo(() => {
    const value = Number(new URLSearchParams(location.search).get('versionNo'));
    return Number.isInteger(value) && value > 0 ? value : undefined;
  }, [location.search]);
  const [taskTabs, setTaskTabs] = useState<TaskTab[]>([]);
  const [newTabOpen, setNewTabOpen] = useState(false);
  const [dirtyTabs, setDirtyTabs] = useState<Record<string, boolean>>({});
  const [listRefreshKey, setListRefreshKey] = useState(0);

  const addTaskTab = useCallback((taskId: number, knownTitle?: string) => {
    setTaskTabs((current) => {
      if (current.some((item) => item.taskId === taskId)) return current;
      return [...current, { taskId, title: knownTitle || `任务 ${taskId}` }];
    });
    if (!knownTitle) {
      void getTask(taskId).then((task) => {
        setTaskTabs((current) => current.map((item) => item.taskId === taskId ? { ...item, title: task.name } : item));
      }).catch((error) => message.error(`加载任务 ${taskId} 失败：${(error as Error).message}`));
    }
  }, []);

  useEffect(() => {
    const ids = parseStoredTaskIds(sessionStorage.getItem(STORAGE_KEY));
    if (!ids.length) return;
    setTaskTabs(ids.map((taskId) => ({ taskId, title: `任务 ${taskId}` })));
    void Promise.allSettled(ids.map((taskId) => getTask(taskId))).then((results) => {
      const loaded = results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : []);
      const loadedTitles = new Map(loaded.map((task) => [task.id, task.name]));
      setTaskTabs((current) => {
        return current
          .filter((item) => !ids.includes(item.taskId) || loadedTitles.has(item.taskId))
          .map((item) => ({ ...item, title: loadedTitles.get(item.taskId) || item.title }));
      });
    });
  }, []);

  useEffect(() => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(taskTabs.map((item) => item.taskId)));
  }, [taskTabs]);

  useEffect(() => {
    if (route.activeKey === 'new') setNewTabOpen(true);
    if ('taskId' in route && route.taskId) {
      addTaskTab(route.taskId);
      setTaskTabs((current) => current.map((item) => item.taskId === route.taskId ? { ...item, versionNo: routeVersionNo } : item));
    }
  }, [addTaskTab, route, routeVersionNo]);

  const openTask = useCallback((taskId: number, title?: string, versionNo?: number) => {
    addTaskTab(taskId, title);
    navigate(`/tasks/${taskId}/edit${versionNo ? `?versionNo=${versionNo}` : ''}`);
  }, [addTaskTab, navigate]);

  const openNew = useCallback(() => {
    setNewTabOpen(true);
    navigate('/tasks/new');
  }, [navigate]);

  const openExecutions = useCallback((taskId: number, executionId?: number) => {
    navigate(executionId
      ? `/tasks/${taskId}/executions/${executionId}`
      : `/tasks/${taskId}/executions`);
  }, [navigate]);

  const closeExecutions = useCallback(() => navigate('/tasks'), [navigate]);

  const closeTab = useCallback((key: string) => {
    const finishClose = () => {
      if (key === 'new') setNewTabOpen(false);
      if (key.startsWith('task:')) {
        const taskId = Number(key.slice(5));
        setTaskTabs((current) => current.filter((item) => item.taskId !== taskId));
      }
      setDirtyTabs((current) => ({ ...current, [key]: false }));
      if (route.activeKey !== key) return;
      const keys = ['list', ...taskTabs.map((item) => `task:${item.taskId}`), ...(newTabOpen ? ['new'] : [])];
      const closingIndex = Math.max(1, keys.indexOf(key));
      const nextKey = keys[closingIndex - 1] || 'list';
      if (nextKey.startsWith('task:')) {
        const nextTask = taskTabs.find((item) => item.taskId === Number(nextKey.slice(5)));
        navigate(`/tasks/${nextKey.slice(5)}/edit${nextTask?.versionNo ? `?versionNo=${nextTask.versionNo}` : ''}`);
      }
      else if (nextKey === 'new') navigate('/tasks/new');
      else navigate('/tasks');
    };
    if (!dirtyTabs[key]) {
      finishClose();
      return;
    }
    Modal.confirm({
      title: '关闭未保存的任务？',
      content: '当前标签中有未保存修改，关闭后本地修改将丢失。',
      okText: '放弃修改并关闭',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: finishClose,
    });
  }, [dirtyTabs, navigate, newTabOpen, route.activeKey, taskTabs]);

  const handleCreated = useCallback((created: SqlTaskVO) => {
    setNewTabOpen(false);
    setTaskTabs((current) => current.some((item) => item.taskId === created.id)
      ? current.map((item) => item.taskId === created.id ? { ...item, title: created.name } : item)
      : [...current, { taskId: created.id, title: created.name }]);
    setDirtyTabs((current) => ({ ...current, new: false, [`task:${created.id}`]: false }));
    setListRefreshKey((current) => current + 1);
    navigate(`/tasks/${created.id}/edit`, { replace: true });
  }, [navigate]);

  const tabItems = [
    {
      key: 'list',
      label: <span className="task-workspace-tab-label"><ProfileOutlined />任务列表</span>,
      closable: false,
      children: (
        <TaskListPage
          embedded
          refreshKey={listRefreshKey}
          initialExecutionTaskId={'executionTaskId' in route ? route.executionTaskId : undefined}
          initialExecutionId={'executionId' in route ? route.executionId : undefined}
          onCreateTask={openNew}
          onEditTask={openTask}
          onExecutionOpen={openExecutions}
          onExecutionClose={closeExecutions}
        />
      ),
    },
    ...taskTabs.map((item) => ({
      key: `task:${item.taskId}`,
      label: <span className="task-workspace-tab-label">{item.title}{item.versionNo ? <small>v{item.versionNo}</small> : null}{dirtyTabs[`task:${item.taskId}`] ? <i /> : null}</span>,
      closable: true,
      children: (
        <TaskEditorPage
          taskId={item.taskId}
          versionNo={item.versionNo}
          onDirtyChange={(dirty) => setDirtyTabs((current) => ({ ...current, [`task:${item.taskId}`]: dirty }))}
          onTaskSaved={(task) => {
            setTaskTabs((current) => current.map((tab) => tab.taskId === task.id ? { ...tab, title: task.name } : tab));
            setListRefreshKey((current) => current + 1);
          }}
          onOpenExecutions={openExecutions}
        />
      ),
    })),
    ...(newTabOpen ? [{
      key: 'new',
      label: <span className="task-workspace-tab-label">新建任务{dirtyTabs.new ? <i /> : null}</span>,
      closable: true,
      children: (
        <TaskEditorPage
          mode="create"
          onDirtyChange={(dirty) => setDirtyTabs((current) => ({ ...current, new: dirty }))}
          onTaskSaved={handleCreated}
        />
      ),
    }] : []),
  ];

  return (
    <div className="task-workspace-page">
      <Tabs
        className="task-workspace-tabs ui-flat-tabs"
        type="editable-card"
        hideAdd
        activeKey={route.activeKey}
        items={tabItems}
        destroyOnHidden={false}
        onChange={(key) => {
          if (key === 'list') navigate('/tasks');
          else if (key === 'new') navigate('/tasks/new');
          else {
            const target = taskTabs.find((item) => item.taskId === Number(key.slice(5)));
            navigate(`/tasks/${key.slice(5)}/edit${target?.versionNo ? `?versionNo=${target.versionNo}` : ''}`);
          }
        }}
        onEdit={(targetKey, action) => {
          if (action === 'remove') closeTab(String(targetKey));
        }}
      />
    </div>
  );
}
