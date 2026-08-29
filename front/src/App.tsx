import { lazy, Suspense, useEffect, useState } from 'react';
import { Spin, message as antdMessage } from 'antd';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { getCurrentUser, logout } from './api/auth';
import AppShell from './components/AppShell';
import ChatPage from './components/ChatPage';
import LoginPage from './components/LoginPage';
import type { LoginUser } from './types';

const SessionManagementPage = lazy(() => import('./components/SessionManagementPage'));
const TaskWorkspacePage = lazy(() => import('./components/tasks/TaskWorkspacePage'));
const ExecutionCenterPage = lazy(() => import('./components/tasks/ExecutionCenterPage'));
const DataCatalogPage = lazy(() => import('./components/catalog/DataCatalogPage'));
const PlatformStatusPage = lazy(() => import('./components/platform/PlatformStatusPage'));
const DataComparePage = lazy(() => import('./components/dataCompare/DataComparePage'));
const VersionComparePage = lazy(() => import('./components/dataCompare/VersionComparePage'));
const DataCompareDetailPage = lazy(() => import('./components/dataCompare/DataCompareDetailPage'));
const TaskVersionUnionPage = lazy(() => import('./components/tasks/TaskVersionUnionPage'));
const RealtimeSyncTasksPage = lazy(() => import('./realtime/pages/RealtimeSyncTasksPage'));
const RealtimeSyncTaskEditorPage = lazy(() => import('./realtime/pages/RealtimeSyncTaskEditorPage'));
const RealtimeServersPage = lazy(() => import('./realtime/pages/RealtimeServersPage'));
const RealtimeAlertsPage = lazy(() => import('./realtime/pages/RealtimeAlertsPage'));
const RealtimeTodoPage = lazy(() => import('./realtime/pages/RealtimeTodoPage'));

export default function App() {
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<LoginUser | null>(null);

  useEffect(() => {
    let active = true;
    void getCurrentUser().then((currentUser) => {
      if (active) {
        setUser(currentUser);
        setLoading(false);
      }
    });
    return () => {
      active = false;
    };
  }, []);

  const handleLogout = async () => {
    try {
      await logout();
    } catch (error) {
      antdMessage.warning(`退出失败：${(error as Error).message}`);
    } finally {
      setUser(null);
    }
  };

  if (loading) {
    return (
      <div className="app-loading">
        <Spin size="large" />
      </div>
    );
  }
  if (!user) return <LoginPage onLoggedIn={setUser} />;
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route
          element={(
            <AppShell
              obId={user.obId}
              onLogout={() => void handleLogout()}
              onSwitchAccount={() => void handleLogout()}
            />
          )}
        >
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/chat/:sessionId" element={<ChatPage />} />
          <Route
            path="/sessions"
            element={(
              <Suspense fallback={<div className="route-loading"><Spin /></div>}>
                <SessionManagementPage />
              </Suspense>
            )}
          />
          <Route path="/tasks" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><TaskWorkspacePage /></Suspense>} />
          <Route path="/tasks/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><TaskWorkspacePage /></Suspense>} />
          <Route path="/tasks/:taskId/edit" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><TaskWorkspacePage /></Suspense>} />
          <Route path="/tasks/:taskId/executions" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><TaskWorkspacePage /></Suspense>} />
          <Route path="/tasks/:taskId/executions/:executionId" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><TaskWorkspacePage /></Suspense>} />
          <Route path="/tasks/unions" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><TaskVersionUnionPage /></Suspense>} />
          <Route path="/executions" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><ExecutionCenterPage /></Suspense>} />
          <Route path="/catalog" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataCatalogPage /></Suspense>} />
          <Route path="/functions" element={<Navigate to="/tasks" replace />} />
          <Route path="/platform" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><PlatformStatusPage /></Suspense>} />
          <Route path="/data-compares" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataComparePage /></Suspense>} />
          <Route path="/data-compares/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><VersionComparePage /></Suspense>} />
          <Route path="/data-compares/:compareId" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataCompareDetailPage /></Suspense>} />
          <Route path="/realtime" element={<Navigate to="/realtime/sync-tasks" replace />} />
          <Route path="/realtime/sync-tasks" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeSyncTasksPage /></Suspense>} />
          <Route path="/realtime/sync-tasks/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeSyncTaskEditorPage /></Suspense>} />
          <Route path="/realtime/sync-tasks/:taskId/edit" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeSyncTaskEditorPage /></Suspense>} />
          <Route path="/realtime/servers" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeServersPage /></Suspense>} />
          <Route path="/realtime/alerts" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeAlertsPage /></Suspense>} />
          <Route path="/realtime/paimon-tables" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeTodoPage title="Paimon 表管理" /></Suspense>} />
          <Route path="/realtime/topics" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeTodoPage title="Topic 管理" /></Suspense>} />
          <Route path="/realtime/compute" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeTodoPage title="实时计算任务" /></Suspense>} />
          <Route path="/realtime/export" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeTodoPage title="实时出仓任务" /></Suspense>} />
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
