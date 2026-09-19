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
const HiveFunctionCatalogPage = lazy(() => import('./components/catalog/HiveFunctionCatalogPage'));
const PlatformStatusPage = lazy(() => import('./components/platform/PlatformStatusPage'));
const DataComparePage = lazy(() => import('./components/dataCompare/DataComparePage'));
const VersionComparePage = lazy(() => import('./components/dataCompare/VersionComparePage'));
const DataCompareDetailPage = lazy(() => import('./components/dataCompare/DataCompareDetailPage'));
const TaskVersionUnionPage = lazy(() => import('./components/tasks/TaskVersionUnionPage'));
const RealtimeSyncWorkspacePage = lazy(() => import('./realtime/pages/RealtimeSyncWorkspacePage'));
const RealtimeServersPage = lazy(() => import('./realtime/pages/RealtimeServersPage'));
const RealtimeAlertsPage = lazy(() => import('./realtime/pages/RealtimeAlertsPage'));
const RealtimeTablesPage = lazy(() => import('./realtime/pages/RealtimeTablesPage'));
const RealtimeManagedTaskWorkspacePage = lazy(() => import('./realtime/pages/RealtimeManagedTaskWorkspacePage'));
const RealtimeTodoPage = lazy(() => import('./realtime/pages/RealtimeTodoPage'));
const OverviewPage = lazy(() => import('./components/overview/OverviewPage'));
const ScheduleDagPage = lazy(() => import('./components/tasks/ScheduleDagPage'));
const BusinessDomainsPage = lazy(() => import('./realtime/pages/BusinessDomainsPage'));
const AssetLineagePage = lazy(() => import('./components/catalog/AssetLineagePage'));
const DataMapOverviewPage = lazy(() => import('./components/dataMap/DataMapOverviewPage'));
const DataMapParsingPage = lazy(() => import('./components/dataMap/DataMapParsingPage'));

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

  useEffect(() => {
    const unauthorized = () => {
      setUser(null);
      antdMessage.error({ key: 'auth-required', content: '登录状态已失效，请重新登录' });
    };
    window.addEventListener('sql-agent:unauthorized', unauthorized);
    return () => window.removeEventListener('sql-agent:unauthorized', unauthorized);
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
          <Route path="/overview" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><OverviewPage /></Suspense>} />
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
          <Route path="/schedules/dag" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><ScheduleDagPage /></Suspense>} />
          <Route path="/catalog" element={<Navigate to="/data-map/catalog" replace />} />
          <Route path="/functions" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><HiveFunctionCatalogPage /></Suspense>} />
          <Route path="/business-domains" element={<Navigate to="/data-map/domains" replace />} />
          <Route path="/assets/lineage" element={<Navigate to="/data-map/lineage" replace />} />
          <Route path="/data-map" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataMapOverviewPage /></Suspense>} />
          <Route path="/data-map/catalog" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataCatalogPage /></Suspense>} />
          <Route path="/data-map/lineage" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><AssetLineagePage /></Suspense>} />
          <Route path="/data-map/domains" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><BusinessDomainsPage /></Suspense>} />
          <Route path="/data-map/parsing" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataMapParsingPage /></Suspense>} />
          <Route path="/platform" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><PlatformStatusPage /></Suspense>} />
          <Route path="/data-compares" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataComparePage /></Suspense>} />
          <Route path="/data-compares/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><VersionComparePage /></Suspense>} />
          <Route path="/data-compares/:compareId" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><DataCompareDetailPage /></Suspense>} />
          <Route path="/realtime" element={<Navigate to="/realtime/sync-tasks" replace />} />
          <Route path="/realtime/sync-tasks" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeSyncWorkspacePage /></Suspense>} />
          <Route path="/realtime/sync-tasks/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeSyncWorkspacePage /></Suspense>} />
          <Route path="/realtime/sync-tasks/:taskId/edit" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeSyncWorkspacePage /></Suspense>} />
          <Route path="/realtime/servers" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeServersPage /></Suspense>} />
          <Route path="/realtime/alerts" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeAlertsPage /></Suspense>} />
          <Route path="/realtime/paimon-tables" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeTablesPage /></Suspense>} />
          <Route path="/realtime/business-domains" element={<Navigate to="/data-map/domains" replace />} />
          <Route path="/realtime/topics" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeTodoPage title="Topic 管理" /></Suspense>} />
          <Route path="/realtime/compute" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeManagedTaskWorkspacePage taskType="compute" /></Suspense>} />
          <Route path="/realtime/compute/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeManagedTaskWorkspacePage taskType="compute" /></Suspense>} />
          <Route path="/realtime/compute/:taskId/edit" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeManagedTaskWorkspacePage taskType="compute" /></Suspense>} />
          <Route path="/realtime/export" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeManagedTaskWorkspacePage taskType="export" /></Suspense>} />
          <Route path="/realtime/export/new" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeManagedTaskWorkspacePage taskType="export" /></Suspense>} />
          <Route path="/realtime/export/:taskId/edit" element={<Suspense fallback={<div className="route-loading"><Spin /></div>}><RealtimeManagedTaskWorkspacePage taskType="export" /></Suspense>} />
          <Route path="*" element={<Navigate to="/overview" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
