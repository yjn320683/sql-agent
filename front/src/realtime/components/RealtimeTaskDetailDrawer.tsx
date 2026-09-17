import { Drawer, Tabs } from 'antd';
import type { ReactNode } from 'react';

export interface RealtimeTaskDetailTab {
  key: string;
  label: ReactNode;
  children: ReactNode;
  disabled?: boolean;
}

interface Props {
  title: ReactNode;
  loading?: boolean;
  activeKey: string;
  tabs: RealtimeTaskDetailTab[];
  onTabChange: (key: string) => void;
  onClose: () => void;
}

/** 三类实时任务共用的底部详情抽屉，类型私有内容由调用方以 Tab 注入。 */
export default function RealtimeTaskDetailDrawer({ title, loading, activeKey, tabs, onTabChange, onClose }: Props) {
  return (
    <Drawer
      className="sync-task-detail-drawer realtime-task-detail-drawer"
      title={title}
      open
      onClose={onClose}
      placement="bottom"
      height="72vh"
      loading={loading}
      destroyOnHidden
    >
      <Tabs className="ui-flat-tabs" activeKey={activeKey} onChange={onTabChange} items={tabs} />
    </Drawer>
  );
}
