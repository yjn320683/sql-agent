import { Button, List, Popconfirm, Tooltip, Typography } from 'antd';
import {
  MessageOutlined,
  InboxOutlined,
  ProfileOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import type { SessionVO } from '../types';

interface Props {
  sessions: SessionVO[];
  activeId: string | null;
  onSelect: (sessionId: string) => void;
  onNew: () => void;
  onArchive: (sessionId: string) => void;
  onManage: () => void;
  disabled?: boolean;
  showHeader?: boolean;
}

export default function SessionSidebar({
  sessions,
  activeId,
  onSelect,
  onNew,
  onArchive,
  onManage,
  disabled = false,
  showHeader = true,
}: Props) {
  return (
    <aside className="recent-sidebar">
      {showHeader ? (
        <div className="recent-sidebar-header">
          <div className="recent-sidebar-title">
            <Typography.Text strong>最近会话</Typography.Text>
            <span>{sessions.length}</span>
          </div>
          <Tooltip title="会话管理">
            <Button
              type="text"
              icon={<ProfileOutlined />}
              aria-label="打开会话管理"
              onClick={onManage}
            />
          </Tooltip>
        </div>
      ) : null}
      <div className="recent-sidebar-content">
        <Button
          type="primary"
          icon={<PlusOutlined />}
          block
          disabled={disabled}
          onClick={onNew}
          className="new-session-button"
        >
          新对话
        </Button>
        <List
          size="small"
          dataSource={sessions}
          locale={{ emptyText: '暂无会话' }}
          renderItem={(item) => (
            <List.Item
              className={item.sessionId === activeId ? 'session-item active' : 'session-item'}
              onClick={() => {
                if (!disabled) onSelect(item.sessionId);
              }}
              actions={[
                <Popconfirm
                  key="archive"
                  title="归档该会话？"
                  disabled={disabled}
                  onConfirm={(e) => {
                    e?.stopPropagation();
                    onArchive(item.sessionId);
                  }}
                  onCancel={(e) => e?.stopPropagation()}
                >
                  <Tooltip title="归档">
                    <Button
                      type="text"
                      size="small"
                      icon={<InboxOutlined />}
                      aria-label={`归档 ${item.title || '未命名会话'}`}
                      onClick={(e) => e.stopPropagation()}
                    />
                  </Tooltip>
                </Popconfirm>,
              ]}
            >
              <div className="session-item-copy">
                <MessageOutlined className="session-item-icon" />
                <Typography.Text ellipsis>{item.title || '未命名会话'}</Typography.Text>
              </div>
            </List.Item>
          )}
        />
      </div>
    </aside>
  );
}
