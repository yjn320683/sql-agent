import { Alert, Descriptions, Modal, Select, Spin, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { getRecoveryOptions, recoverInstance, type RecoveryOptions } from '../api';
import type { TaskInstance } from '../types';

interface Props {
  taskId: number;
  source?: TaskInstance;
  onClose: () => void;
  onRecovered: (instance: TaskInstance) => void;
}

export default function RealtimeRecoveryModal({ taskId, source, onClose, onRecovered }: Props) {
  const [options, setOptions] = useState<RecoveryOptions>();
  const [selected, setSelected] = useState('');
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!source) { setOptions(undefined); setSelected(''); return; }
    setLoading(true);
    void getRecoveryOptions(taskId, source.id).then((value) => {
      setOptions(value);
      const preferred = value.strategies.find((item) => item.available && item.type === 'savepoint')
        ?? value.strategies.find((item) => item.available && item.type === 'checkpoint')
        ?? value.strategies.find((item) => item.available);
      setSelected(preferred ? `${preferred.type}|${preferred.statePath ?? ''}` : '');
    }).catch((error) => message.error((error as Error).message)).finally(() => setLoading(false));
  }, [source, taskId]);

  const strategy = useMemo(() => options?.strategies.find((item) => `${item.type}|${item.statePath ?? ''}` === selected), [options, selected]);
  const submit = async () => {
    if (!source || !strategy?.available) return;
    setSubmitting(true);
    try {
      const instance = await recoverInstance(taskId, source.id, { startType: strategy.type, statePath: strategy.statePath });
      message.success(`已从实例 ${source.id} 创建恢复实例 ${instance.id}`); onRecovered(instance);
    } catch (error) { message.error((error as Error).message); }
    finally { setSubmitting(false); }
  };

  return <Modal title={source ? `恢复实例 ${source.id}` : '恢复实例'} open={Boolean(source)} onCancel={onClose} onOk={() => void submit()} okText="创建恢复实例" cancelText="取消" confirmLoading={submitting} okButtonProps={{ disabled: !strategy?.available }} width={760} destroyOnHidden>
    {loading ? <Spin /> : options && source ? <div className="realtime-recovery-summary">
      <Alert showIcon type="info" message="将创建新实例，来源实例和配置快照不会被修改" description="恢复前会重新校验当前 Catalog、Schema、连接和状态路径。" />
      <Descriptions bordered size="small" column={2} items={[
        { key: 'source', label: '来源实例', children: source.id },
        { key: 'version', label: '版本 ID', children: options.versionId ?? '-' },
        { key: 'status', label: '来源状态', children: source.status },
        { key: 'mode', label: '运行模式', children: source.executionMode },
      ]} />
      <div><Typography.Text strong>恢复方式</Typography.Text><Select value={selected || undefined} onChange={setSelected} style={{ width: '100%', marginTop: 8 }} placeholder="没有可用的恢复方式" options={options.strategies.map((item) => ({ value: `${item.type}|${item.statePath ?? ''}`, disabled: !item.available, label: `${item.type.toUpperCase()}${item.statePath ? ` · ${item.statePath}` : ''}${item.reason ? ` · ${item.reason}` : ''}` }))} /></div>
      {strategy?.statePath && <Typography.Paragraph code copyable>{strategy.statePath}</Typography.Paragraph>}
    </div> : null}
  </Modal>;
}
