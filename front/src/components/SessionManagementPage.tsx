import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Button,
  Drawer,
  Dropdown,
  Empty,
  Grid,
  Input,
  List,
  Modal,
  Pagination,
  Result,
  Segmented,
  Table,
  Tag,
  Tooltip,
  Typography,
  message as antdMessage,
  type TableProps,
} from 'antd';
import {
  ArrowRightOutlined,
  CodeOutlined,
  EditOutlined,
  EyeOutlined,
  InboxOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  RedoOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  archiveSession,
  loadMessages,
  manageSessions,
  renameSession,
  restoreSession,
} from '../api/chat';
import { useAutoTableActionWidth } from '../utils/useAutoTableActionWidth';
import type {
  ChatMessage,
  SessionManageQuery,
  SessionPageVO,
  SessionStatus,
  SessionVO,
} from '../types';
import { historyMessageForDisplay, isVisibleHistoryMessage } from '../utils/messages';
import { queryFromParams, queryToParams } from '../utils/sessionQuery';
import { ApiError } from '../api/client';
import MessageList from './MessageList';

const EMPTY_PAGE: SessionPageVO = { items: [], page: 1, pageSize: 20, total: 0 };
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

function formatDate(value?: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : dateFormatter.format(date);
}

export default function SessionManagementPage() {
  const { actionColumnWidth, actionRef } = useAutoTableActionWidth({ initialWidth: 132 });
  const navigate = useNavigate();
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;
  const [searchParams, setSearchParams] = useSearchParams();
  const searchKey = searchParams.toString();
  const query = useMemo(() => queryFromParams(new URLSearchParams(searchKey)), [searchKey]);
  const [keyword, setKeyword] = useState(query.keyword);
  const [pageData, setPageData] = useState<SessionPageVO>(EMPTY_PAGE);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [previewSession, setPreviewSession] = useState<SessionVO | null>(null);
  const [previewMessages, setPreviewMessages] = useState<ChatMessage[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [renameTarget, setRenameTarget] = useState<SessionVO | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  const [saving, setSaving] = useState(false);
  const pageRequestRef = useRef(0);
  const previewRequestRef = useRef(0);

  const updateQuery = useCallback((patch: Partial<SessionManageQuery>) => {
    const next = { ...query, ...patch };
    setSearchParams(queryToParams(next));
  }, [query, setSearchParams]);

  const loadPage = useCallback(async () => {
    const requestId = pageRequestRef.current + 1;
    pageRequestRef.current = requestId;
    setLoading(true);
    setLoadError(null);
    try {
      const result = await manageSessions(query);
      if (pageRequestRef.current === requestId) setPageData(result);
    } catch (error) {
      if (pageRequestRef.current === requestId) {
        const message = error instanceof ApiError && error.status === 404
          ? '当前 Backend 未提供会话管理接口，请重新构建并启动最新版本。'
          : (error as Error).message;
        setLoadError(message);
      }
    } finally {
      if (pageRequestRef.current === requestId) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void loadPage();
  }, [loadPage]);

  useEffect(() => {
    setKeyword(query.keyword);
  }, [query.keyword]);

  useEffect(() => {
    const normalized = keyword.trim();
    if (normalized === query.keyword) return undefined;
    const timer = window.setTimeout(() => updateQuery({ keyword: normalized, page: 1 }), 350);
    return () => window.clearTimeout(timer);
  }, [keyword, query.keyword, updateQuery]);

  const openPreview = async (session: SessionVO) => {
    const requestId = previewRequestRef.current + 1;
    previewRequestRef.current = requestId;
    setPreviewSession(session);
    setPreviewMessages([]);
    setPreviewLoading(true);
    try {
      const messages = await loadMessages(session.sessionId);
      if (previewRequestRef.current === requestId) {
        setPreviewMessages(
          messages.filter(isVisibleHistoryMessage).map(historyMessageForDisplay),
        );
      }
    } catch (error) {
      if (previewRequestRef.current === requestId) {
        antdMessage.error(`加载会话历史失败：${(error as Error).message}`);
      }
    } finally {
      if (previewRequestRef.current === requestId) setPreviewLoading(false);
    }
  };

  const archive = (session: SessionVO) => {
    Modal.confirm({
      title: '归档会话',
      content: `确认归档“${session.title || '未命名会话'}”？历史记录仍会保留。`,
      okText: '归档',
      cancelText: '取消',
      onOk: async () => {
        try {
          await archiveSession(session.sessionId);
          if (previewSession?.sessionId === session.sessionId) setPreviewSession(null);
          antdMessage.success('会话已归档');
          await loadPage();
        } catch (error) {
          antdMessage.error(`归档失败：${(error as Error).message}`);
          throw error;
        }
      },
    });
  };

  const restore = async (session: SessionVO, continueChat = false) => {
    try {
      await restoreSession(session.sessionId);
      antdMessage.success('会话已恢复');
      if (continueChat) {
        navigate(`/chat/${session.sessionId}`);
        return;
      }
      setPreviewSession((current) => (
        current?.sessionId === session.sessionId ? { ...current, archived: false } : current
      ));
      await loadPage();
    } catch (error) {
      antdMessage.error(`恢复失败：${(error as Error).message}`);
    }
  };

  const saveRename = async () => {
    if (!renameTarget) return;
    const title = renameTitle.trim();
    if (!title || title.length > 64) {
      antdMessage.warning('会话标题长度必须在1到64个字符之间');
      return;
    }
    setSaving(true);
    try {
      await renameSession(renameTarget.sessionId, title);
      setPreviewSession((current) => (
        current?.sessionId === renameTarget.sessionId ? { ...current, title } : current
      ));
      setRenameTarget(null);
      antdMessage.success('会话标题已更新');
      await loadPage();
    } catch (error) {
      antdMessage.error(`重命名失败：${(error as Error).message}`);
    } finally {
      setSaving(false);
    }
  };

  const continueChat = async (session: SessionVO) => {
    if (session.archived) {
      await restore(session, true);
    } else {
      navigate(`/chat/${session.sessionId}`);
    }
  };

  const actionButtons = (session: SessionVO) => (
    <div ref={actionRef(session.sessionId)} className="session-row-actions table-row-actions icon-row-actions" onClick={(event) => event.stopPropagation()}>
      <Tooltip title="预览">
        <Button
          type="text"
          icon={<EyeOutlined />}
          aria-label={`预览 ${session.title || '未命名会话'}`}
          onClick={() => void openPreview(session)}
        />
      </Tooltip>
      <Tooltip title="继续对话">
        <Button
          type="text"
          icon={<ArrowRightOutlined />}
          aria-label={`继续对话 ${session.title || '未命名会话'}`}
          onClick={() => void continueChat(session)}
        />
      </Tooltip>
      <Dropdown
        trigger={['click']}
        placement="bottomRight"
        menu={{
          items: [
            { key: 'rename', icon: <EditOutlined />, label: '重命名' },
            session.archived
              ? { key: 'restore', icon: <RedoOutlined />, label: '恢复会话' }
              : { key: 'archive', icon: <InboxOutlined />, label: '归档会话' },
          ],
          onClick: ({ key }) => {
            if (key === 'rename') {
              setRenameTarget(session);
              setRenameTitle(session.title ?? '');
            }
            if (key === 'restore') void restore(session);
            if (key === 'archive') archive(session);
          },
        }}
      >
        <Tooltip title="更多操作">
          <Button type="text" icon={<MoreOutlined />} aria-label={`更多操作 ${session.title || '未命名会话'}`} />
        </Tooltip>
      </Dropdown>
    </div>
  );

  const columns: ColumnsType<SessionVO> = [
    {
      title: '会话标题',
      dataIndex: 'title',
      key: 'title',
      width: 320,
      fixed: 'left',
      ellipsis: true,
      render: (title: string) => (
        <div className="session-title-cell">
          <span className="session-type-icon"><CodeOutlined /></span>
          <Typography.Text strong ellipsis>{title || '未命名会话'}</Typography.Text>
        </div>
      ),
    },
    {
      title: '状态',
      dataIndex: 'archived',
      key: 'archived',
      width: 92,
      fixed: 'left',
      render: (archived: boolean) => (
        <span className={archived ? 'session-status archived' : 'session-status active'}>
          <span />{archived ? '已归档' : '活跃'}
        </span>
      ),
    },
    {
      title: '来源',
      key: 'context',
      width: 260,
      ellipsis: true,
      render: (_, session) => session.contextType ? (
        <div className="session-context-cell">
          <Tag color="blue">页面 AI</Tag>
          <Typography.Text ellipsis>{session.contextTitle || session.contextType}</Typography.Text>
        </div>
      ) : <Typography.Text type="secondary">SQL 助手</Typography.Text>,
    },
    {
      title: '最后活跃',
      dataIndex: 'lastActiveAt',
      key: 'lastActiveAt',
      width: 172,
      sorter: true,
      sortOrder: query.sortBy === 'lastActiveAt' ? (query.sortOrder === 'asc' ? 'ascend' : 'descend') : null,
      render: formatDate,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 172,
      responsive: ['xl'],
      sorter: true,
      sortOrder: query.sortBy === 'createdAt' ? (query.sortOrder === 'asc' ? 'ascend' : 'descend') : null,
      render: formatDate,
    },
    {
      title: '会话 ID',
      dataIndex: 'sessionId',
      key: 'sessionId',
      width: 210,
      responsive: ['xl'],
      ellipsis: true,
      render: (sessionId: string) => (
        <Typography.Text className="session-id" copyable={{ text: sessionId }} ellipsis>
          {sessionId}
        </Typography.Text>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: actionColumnWidth,
      fixed: 'right',
      className: 'table-operation-column',
      render: (_, session) => actionButtons(session),
    },
  ];

  const handleTableChange: NonNullable<TableProps<SessionVO>['onChange']> = (
    pagination,
    _filters,
    sorter,
  ) => {
    const currentSorter = Array.isArray(sorter) ? sorter[0] : sorter;
    const field = currentSorter?.field;
    updateQuery({
      page: pagination.current ?? 1,
      pageSize: pagination.pageSize ?? 20,
      ...(field === 'createdAt' || field === 'lastActiveAt'
        ? {
            sortBy: field,
            sortOrder: currentSorter.order === 'ascend' ? 'asc' : 'desc',
          }
        : {}),
    });
  };

  return (
    <section className="session-management">
      <div className="session-management-inner">
        <div className="session-data-surface">
          <div className="session-toolbar">
            <div className="session-filter-group">
              <span className="session-toolbar-label">会话状态</span>
              <Segmented
                className="ui-flat-segmented"
                value={query.status}
                options={[
                  { label: '活跃', value: 'active' },
                  { label: '已归档', value: 'archived' },
                  { label: '全部', value: 'all' },
                ]}
                onChange={(value) => updateQuery({ status: value as SessionStatus, page: 1 })}
              />
            </div>
            <div className="session-toolbar-actions">
              <span className="session-page-count">{loadError ? '连接异常' : `共 ${pageData.total} 个会话`}</span>
              <Input
                className="session-search"
                prefix={<SearchOutlined />}
                placeholder="搜索标题或会话 ID"
                value={keyword}
                allowClear
                onChange={(event) => setKeyword(event.target.value)}
              />
              <Tooltip title="刷新">
                <Button icon={<ReloadOutlined />} aria-label="刷新会话" onClick={() => void loadPage()} />
              </Tooltip>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/chat')}>新建对话</Button>
            </div>
          </div>

          {loadError ? (
            <Result
              className="session-error-state"
              status="error"
              title="会话加载失败"
              subTitle={loadError}
              extra={<Button type="primary" icon={<ReloadOutlined />} onClick={() => void loadPage()}>重新加载</Button>}
            />
          ) : isMobile ? (
        <div className="session-mobile-list">
          <List
            loading={loading}
            dataSource={pageData.items}
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的会话">
                  <Button type="link" onClick={() => navigate('/chat')}>开始新对话</Button>
                </Empty>
              ),
            }}
            renderItem={(session) => (
              <List.Item className="session-mobile-item" onClick={() => void openPreview(session)}>
                <div className="session-mobile-heading">
                  <Typography.Text strong ellipsis>{session.title || '未命名会话'}</Typography.Text>
                  <span className={session.archived ? 'session-status archived' : 'session-status active'}>
                    <span />{session.archived ? '已归档' : '活跃'}
                  </span>
                </div>
                <div className="session-mobile-meta">最后活跃 {formatDate(session.lastActiveAt)}</div>
                <div className="session-mobile-actions">{actionButtons(session)}</div>
              </List.Item>
            )}
          />
          {pageData.total > 0 ? (
            <Pagination
              size="small"
              current={query.page}
              pageSize={query.pageSize}
              total={pageData.total}
              showSizeChanger={false}
              onChange={(page) => updateQuery({ page })}
            />
          ) : null}
        </div>
          ) : (
        <Table
          rowKey="sessionId"
          loading={loading}
          tableLayout="fixed"
          columns={columns}
          dataSource={pageData.items}
          scroll={{ x: 966 + actionColumnWidth }}
          pagination={{
            size: 'small',
            current: query.page,
            pageSize: query.pageSize,
            total: pageData.total,
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 50, 100],
            showTotal: (total) => `共 ${total} 条`,
          }}
          locale={{
            emptyText: (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的会话">
                <Button type="link" onClick={() => navigate('/chat')}>开始新对话</Button>
              </Empty>
            ),
          }}
          onChange={handleTableChange}
          onRow={(session) => ({ onClick: () => void openPreview(session) })}
        />
          )}
        </div>
      </div>

      <Drawer
        className="session-preview-drawer"
        title={previewSession?.title || '会话预览'}
        width={isMobile ? '100%' : 600}
        open={Boolean(previewSession)}
        loading={previewLoading}
        onClose={() => {
          previewRequestRef.current += 1;
          setPreviewSession(null);
        }}
        extra={previewSession ? (
          <Button type="primary" icon={<ArrowRightOutlined />} onClick={() => void continueChat(previewSession)}>
            {previewSession.archived ? '恢复并继续' : '继续对话'}
          </Button>
        ) : null}
      >
        {previewSession ? (
          <>
            <div className="preview-meta">
              <Tag color={previewSession.archived ? 'gold' : 'green'}>
                {previewSession.archived ? '已归档' : '活跃'}
              </Tag>
              <span>创建于 {formatDate(previewSession.createdAt)}</span>
              <span>最后活跃 {formatDate(previewSession.lastActiveAt)}</span>
            </div>
            <div className="preview-session-id">{previewSession.sessionId}</div>
            <div className="preview-chat-history">
              {previewMessages.length ? (
                <MessageList
                  messages={previewMessages}
                  readOnly
                  onPermissionDecision={() => undefined}
                  onUserQuestionAnswer={() => undefined}
                />
              ) : <Empty description="暂无历史消息" />}
            </div>
          </>
        ) : null}
      </Drawer>

      <Modal
        title="重命名会话"
        open={Boolean(renameTarget)}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onOk={() => void saveRename()}
        onCancel={() => setRenameTarget(null)}
      >
        <Input
          value={renameTitle}
          maxLength={64}
          showCount
          autoFocus
          onChange={(event) => setRenameTitle(event.target.value)}
          onPressEnter={() => void saveRename()}
        />
      </Modal>
    </section>
  );
}
