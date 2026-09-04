import { Descriptions, Drawer, Table, Tabs, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { listInstances, listVersions } from '../api';
import type { ManagedTask, TaskInstance } from '../types';
import InstanceLogPanel from './InstanceLogPanel';

interface Props {
  task?: ManagedTask;
  onClose: () => void;
}

const statusText: Record<string, string> = {
  not_running: '未运行', submitting: '提交中', running: '运行中', stopping: '停止中',
  finished: '已完成', failed: '失败', canceled: '已取消', killed_success: '调试成功',
};

export default function RealtimeManagedTaskDetailModal({ task, onClose }: Props) {
  const [versions, setVersions] = useState<Record<string, unknown>[]>([]);
  const [instances, setInstances] = useState<TaskInstance[]>([]);
  const [selected, setSelected] = useState<TaskInstance>();

  useEffect(() => {
    setSelected(undefined);
    if (!task) { setVersions([]); setInstances([]); return; }
    void Promise.all([listVersions(task.id), listInstances(task.id), listInstances(task.id, 'DEBUG')])
      .then(([versionRows, production, debug]) => { setVersions(versionRows); setInstances([...production, ...debug]); });
  }, [task]);

  return <Drawer className="sync-task-detail-drawer" title={task?.name || '任务详情'} open={Boolean(task)} onClose={onClose} placement="bottom" height="72vh" destroyOnHidden>
    {task && (selected ? <InstanceLogPanel taskId={task.id} instance={selected} backLabel="返回实例列表" onBack={() => setSelected(undefined)} /> : <Tabs items={[
      { key: 'base', label: '基本信息', children: <>
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="任务 ID">{task.id}</Descriptions.Item>
          <Descriptions.Item label="状态"><Tag>{statusText[task.status] || task.status}</Tag></Descriptions.Item>
          <Descriptions.Item label="负责人">{task.owner}</Descriptions.Item>
          <Descriptions.Item label="Flink">{task.flinkVersion}</Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>{task.description || '-'}</Descriptions.Item>
          <Descriptions.Item label="表依赖" span={2}>{task.tableReferences?.map((item) => `${item.referenceRole}: ${item.databaseName}.${item.tableName}`).join('；') || '-'}</Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5}>类型私有配置</Typography.Title>
        <pre className="managed-task-json-detail">{JSON.stringify(task.taskConfig, null, 2)}</pre>
      </> },
      { key: 'versions', label: `版本（${versions.length}）`, children: <Table size="small" rowKey={(row) => String(row.id)} pagination={false} dataSource={versions} columns={[
        { title: '版本', dataIndex: 'versionNo' }, { title: '操作人', dataIndex: 'operator' }, { title: '创建时间', dataIndex: 'createTime' },
      ]} /> },
      { key: 'instances', label: `运行实例（${instances.length}）`, children: <Table size="small" rowKey="id" pagination={false} dataSource={instances} columns={[
        { title: '实例', dataIndex: 'id' }, { title: '模式', dataIndex: 'executionMode' },
        { title: '状态', render: (_, row) => statusText[row.status] || row.status },
        { title: 'Job ID', dataIndex: 'jobId' }, { title: '启动时间', dataIndex: 'startedAt' },
        { title: '日志', render: (_, row) => <Typography.Link onClick={() => setSelected(row)}>查看实例日志</Typography.Link> },
      ]} /> },
    ]} />)}
  </Drawer>;
}
