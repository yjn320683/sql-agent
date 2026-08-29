import { Empty, Tag, Typography } from 'antd';

export default function RealtimeTodoPage({ title }: { title: string }) {
  return (
    <div className="realtime-todo-page">
      <Empty description={<div><Typography.Title level={4}>{title}</Typography.Title><Typography.Paragraph type="secondary">首期只迁移实时同步能力，该模块已按计划保留扩展入口。</Typography.Paragraph><Tag color="processing">TODO</Tag></div>} />
    </div>
  );
}
