import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ChangeDetailContent } from './SyncTaskDetailDrawer';

const snapshot = (domainPrefix: string) => ({
  taskType: 'sync',
  name: '订单同步',
  owner: 'admin',
  description: '订单 CDC',
  flinkVersion: '2.2.1',
  alarmConfig: { alarmType: 'task-failed', alarmGroup: '实时值班组' },
  taskConfig: {
    sourceType: 'mysql-cdc',
    sourceServerId: 7,
    cdcConfig: {
      databaseName: 'orders',
      selectedTables: ['order_info'],
      targetDatabase: 'ods_real',
      domainPrefix,
      tablePrefix: `ods_real_orders_${domainPrefix}_`,
      targetTableList: [`ods_real_orders_${domainPrefix}_order_info`],
      metadataColumns: ['database_name', 'table_name', 'op_ts'],
      mode: 'divided',
      tableConfigs: {},
    },
  },
  flinkConf: { parallelism: 2, checkpointIntervalSeconds: 60, taskManagerMemoryGb: 3, jobManagerMemoryGb: 1 },
});

describe('实时同步变更记录详情', () => {
  it('编辑记录按参考项目左右展示完整配置并原位标记变更', () => {
    const html = renderToStaticMarkup(<ChangeDetailContent detail={{
      detailKind: 'edit',
      beforeConfig: snapshot('trade'),
      afterConfig: snapshot('retail'),
      diffs: [{ path: 'taskConfig.cdcConfig.domainPrefix', label: '目标 Paimon 表所属域' }],
    }} sourceServerName="订单库" />);

    expect(html).toContain('task-change-detail-split');
    expect(html).toContain('变更前');
    expect(html).toContain('变更后');
    expect(html).toContain('公共配置');
    expect(html).toContain('目标Paimon表所属域');
    expect(html).toContain('task-detail-field-changed');
    expect(html).not.toContain('本次变更字段');
  });

  it('创建记录只展示创建后的业务配置，不展开原始响应对象', () => {
    const html = renderToStaticMarkup(<ChangeDetailContent detail={{
      detailKind: 'create',
      afterConfig: snapshot('trade'),
      operationRequest: { internal: 'raw' },
    }} />);

    expect(html).toContain('基础信息');
    expect(html).toContain('源端&amp;目标Paimon配置');
    expect(html).not.toContain('operationRequest');
  });
});
