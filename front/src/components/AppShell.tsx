import { useEffect, useState, type ReactNode } from 'react';
import { Avatar, Dropdown, Grid, Segmented, Tooltip } from 'antd';
import {
  CodeOutlined,
  DatabaseOutlined,
  HistoryOutlined,
  MonitorOutlined,
  ProfileOutlined,
  LogoutOutlined,
  CloudServerOutlined,
  SwapOutlined,
  ExperimentOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  AppstoreOutlined,
  BranchesOutlined,
  SettingOutlined,
  ApiOutlined,
  DownOutlined,
  NodeIndexOutlined,
  RightOutlined,
  RocketOutlined,
  UserOutlined,
  DashboardOutlined,
  SearchOutlined,
  AlertOutlined,
  FunctionOutlined,
  ApartmentOutlined,
  RadarChartOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { modeOf, storageKeyOf, validRememberedPath } from '../realtime/mode';
import RouteAiAssistant from './ai/RouteAiAssistant';
import GlobalSearchPalette from './search/GlobalSearchPalette';

interface Props {
  obId: string;
  onSwitchAccount: () => void;
  onLogout: () => void;
}

interface NavigationGroup {
  key: string;
  label: string;
  icon: ReactNode;
  items: Array<{ path: string; label: string; icon: ReactNode }>;
}

const navigationCollapsedKey = 'sql-agent-navigation-collapsed';

const offlineNavigation: NavigationGroup[] = [
  {
    key: 'assistant', label: '智能协作', icon: <AppstoreOutlined />, items: [
      { path: '/chat', label: 'SQL 助手', icon: <CodeOutlined /> },
      { path: '/sessions', label: '会话管理', icon: <HistoryOutlined /> },
    ],
  },
  {
    key: 'development', label: '离线研发', icon: <BranchesOutlined />, items: [
      { path: '/tasks', label: '任务管理', icon: <ProfileOutlined /> },
      { path: '/executions', label: '执行中心', icon: <MonitorOutlined /> },
      { path: '/schedules/dag', label: '调度拓扑', icon: <NodeIndexOutlined /> },
      { path: '/data-compares', label: '数据验数', icon: <ExperimentOutlined /> },
    ],
  },
  {
    key: 'assets', label: '数据资产', icon: <DatabaseOutlined />, items: [
      { path: '/functions', label: '函数目录', icon: <FunctionOutlined /> },
    ],
  },
  {
    key: 'system', label: '系统管理', icon: <SettingOutlined />, items: [
      { path: '/platform', label: '平台状态', icon: <CloudServerOutlined /> },
    ],
  },
];

const realtimeNavigation: NavigationGroup[] = [
  {
    key: 'tasks', label: '任务管理', icon: <AppstoreOutlined />, items: [
      { path: '/realtime/sync-tasks', label: '实时同步任务', icon: <RocketOutlined /> },
      { path: '/realtime/compute', label: '实时计算任务', icon: <NodeIndexOutlined /> },
      { path: '/realtime/export', label: '实时出仓任务', icon: <ApiOutlined /> },
    ],
  },
  {
    key: 'sources', label: '数据源管理', icon: <DatabaseOutlined />, items: [
      { path: '/realtime/servers', label: 'Server 管理', icon: <CloudServerOutlined /> },
      { path: '/realtime/paimon-tables', label: '实时表管理', icon: <DatabaseOutlined /> },
    ],
  },
  {
    key: 'operations', label: '运维中心', icon: <MonitorOutlined />, items: [
      { path: '/realtime/alerts', label: '告警中心', icon: <AlertOutlined /> },
    ],
  },
];

const workspaceNavigation: NavigationGroup[] = [{
  key: 'workspace', label: '统一入口', icon: <DashboardOutlined />, items: [
    { path: '/overview', label: '统一工作台', icon: <DashboardOutlined /> },
  ],
}];

const dataMapNavigation: NavigationGroup[] = [
  {
    key: 'data-map', label: '数据地图', icon: <ApartmentOutlined />, items: [
      { path: '/data-map', label: '数据概览', icon: <DashboardOutlined /> },
      { path: '/data-map/catalog', label: '数据目录', icon: <DatabaseOutlined /> },
      { path: '/data-map/lineage', label: '血缘分析', icon: <NodeIndexOutlined /> },
      { path: '/data-map/domains', label: '业务域', icon: <BranchesOutlined /> },
      { path: '/data-map/parsing', label: '解析监控', icon: <RadarChartOutlined /> },
    ],
  },
];

export default function AppShell({ obId, onSwitchAccount, onLogout }: Props) {
  const location = useLocation();
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const [navigationCollapsed, setNavigationCollapsed] = useState(
    () => window.localStorage.getItem(navigationCollapsedKey) === 'true',
  );
  const [collapsedRealtimeGroups, setCollapsedRealtimeGroups] = useState<Set<string>>(() => new Set());
  const [searchOpen, setSearchOpen] = useState(false);
  const iconOnlyNavigation = navigationCollapsed || !screens.xl;
  const applicationMode = modeOf(location.pathname);
  const realtime = applicationMode === 'realtime';
  const navigation = applicationMode === 'workspace'
    ? workspaceNavigation
    : applicationMode === 'data-map' ? dataMapNavigation : realtime ? realtimeNavigation : offlineNavigation;

  useEffect(() => {
    if (applicationMode === 'workspace') return;
    const key = storageKeyOf(applicationMode);
    window.localStorage.setItem(key, `${location.pathname}${location.search}`);
  }, [applicationMode, location.pathname, location.search]);

  const switchMode = (mode: string | number) => {
    if (mode === 'workspace') { navigate('/overview'); return; }
    const targetMode = mode === 'realtime' ? 'realtime' : mode === 'data-map' ? 'data-map' : 'offline';
    const remembered = window.localStorage.getItem(storageKeyOf(targetMode));
    navigate(validRememberedPath(targetMode, remembered));
  };

  const toggleNavigation = () => {
    setNavigationCollapsed((collapsed) => {
      const next = !collapsed;
      window.localStorage.setItem(navigationCollapsedKey, String(next));
      return next;
    });
  };

  const toggleRealtimeGroup = (key: string) => {
    setCollapsedRealtimeGroups((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const navigationClassName = [
    'app-nav',
    applicationMode,
    navigationCollapsed ? 'collapsed' : '',
  ].filter(Boolean).join(' ');

  useEffect(() => {
    const openSearch = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener('keydown', openSearch);
    return () => window.removeEventListener('keydown', openSearch);
  }, []);

  return (
    <div className="app-shell">
      <aside className={navigationClassName}>
        <div className="app-brand" aria-label="SQL Agent">
          <span className="brand-mark"><DatabaseOutlined /></span>
          <span className="brand-copy">
            <span className="brand-name">SQL Agent</span>
            <span className="brand-edition">DATA WORKSPACE</span>
          </span>
        </div>

        <nav className="primary-nav" aria-label="主导航">
          {navigation.map((group) => {
            const groupCollapsed = realtime && collapsedRealtimeGroups.has(group.key);
            return (
            <section className={groupCollapsed ? 'primary-nav-group collapsed' : 'primary-nav-group'} key={group.key} aria-label={group.label}>
              {realtime ? (
                <button
                  type="button"
                  className="primary-nav-group-title"
                  aria-expanded={!groupCollapsed}
                  onClick={() => toggleRealtimeGroup(group.key)}
                >
                  <span className="primary-nav-group-icon">{group.icon}</span>
                  <span className="primary-nav-group-label">{group.label}</span>
                  <span className="primary-nav-group-caret">{groupCollapsed ? <RightOutlined /> : <DownOutlined />}</span>
                </button>
              ) : (
                <div className="primary-nav-group-title">
                  <span className="primary-nav-group-icon">{group.icon}</span>
                  <span className="primary-nav-group-label">{group.label}</span>
                </div>
              )}
              <div
                className="primary-nav-group-items"
                aria-hidden={groupCollapsed && !navigationCollapsed && screens.md !== false}
              >
                {group.items.map((item) => {
                  const active = item.path === '/data-map'
                    ? location.pathname === item.path
                    : location.pathname.startsWith(item.path);
                  return (
                    <Tooltip key={item.path} title={iconOnlyNavigation ? item.label : undefined} placement="right">
                      <button
                        type="button"
                        className={active ? 'primary-nav-item active' : 'primary-nav-item'}
                        onClick={() => navigate(item.path)}
                      >
                        <span className="primary-nav-icon">{item.icon}</span>
                        <span className="primary-nav-label">{item.label}</span>
                      </button>
                    </Tooltip>
                  );
                })}
              </div>
            </section>
            );
          })}
        </nav>

        <Tooltip title={navigationCollapsed ? '展开菜单' : '收起菜单'} placement="right">
          <button
            type="button"
            className="nav-collapse-entry"
            aria-label={navigationCollapsed ? '展开菜单' : '收起菜单'}
            onClick={toggleNavigation}
          >
            {navigationCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            <span className="nav-collapse-label">收起菜单</span>
          </button>
        </Tooltip>

      </aside>

      <main className="app-workspace">
        <header className="app-mode-header">
          <Segmented
            className="app-mode-switch ui-flat-segmented"
            value={applicationMode}
            onChange={switchMode}
            options={[{ label: '工作台', value: 'workspace' }, { label: '离线', value: 'offline' }, { label: '实时', value: 'realtime' }, { label: '数据地图', value: 'data-map' }]}
          />
          <div className="app-mode-actions">
            <Tooltip title="全局搜索（Ctrl / ⌘ + K）" placement="bottom">
              <button
                type="button"
                className="global-search-entry"
                aria-label="打开全局搜索"
                onClick={() => setSearchOpen(true)}
              >
                <SearchOutlined />
              </button>
            </Tooltip>
            <Dropdown
              trigger={['click']}
              placement="bottomRight"
              menu={{
                items: [
                  { key: 'switch', icon: <SwapOutlined />, label: '切换账号' },
                  { type: 'divider' },
                  { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true },
                ],
                onClick: ({ key }) => {
                  if (key === 'switch') onSwitchAccount();
                  if (key === 'logout') onLogout();
                },
              }}
            >
              <button type="button" className="header-account-entry" aria-label={`当前用户 ${obId}`}>
                <Avatar size={28} className="account-avatar" icon={<UserOutlined />} />
                <span className="header-account-id">{obId}</span>
                <DownOutlined className="header-account-caret" />
              </button>
            </Dropdown>
          </div>
        </header>
        <div className="app-mode-content"><Outlet /></div>
        <RouteAiAssistant />
        <GlobalSearchPalette open={searchOpen} onClose={() => setSearchOpen(false)} />
      </main>
    </div>
  );
}
