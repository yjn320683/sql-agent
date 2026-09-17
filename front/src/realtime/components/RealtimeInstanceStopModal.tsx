import { Alert, Checkbox, Modal, Space } from 'antd';
import { useEffect, useState } from 'react';
import type { TaskInstance } from '../types';

interface Props {
  instance?: TaskInstance;
  loading?: boolean;
  onClose: () => void;
  onConfirm: (method: 'savepoint' | 'direct') => Promise<void> | void;
}

export default function RealtimeInstanceStopModal({ instance, loading, onClose, onConfirm }: Props) {
  const [allowDirectStop, setAllowDirectStop] = useState(false);

  useEffect(() => setAllowDirectStop(false), [instance?.id]);

  return (
    <Modal
      title={`停止实例 ${instance?.id ?? ''}`}
      open={Boolean(instance)}
      okText="确认停止"
      cancelText="取消"
      confirmLoading={loading}
      onOk={() => instance && void onConfirm(allowDirectStop ? 'direct' : 'savepoint')}
      onCancel={onClose}
      destroyOnHidden
    >
      {instance && instance.status !== 'running' && !allowDirectStop && (
        <Alert
          showIcon
          type="error"
          message="当前正式实例不是运行中状态，无法生成 Savepoint"
          description="如需停止，请勾选允许直接停止。"
          style={{ marginBottom: 16 }}
        />
      )}
      <Space direction="vertical" style={{ width: '100%' }}>
        <Alert showIcon type="info" message="默认使用 Savepoint 停止" description="会在停止前生成新的 Savepoint，便于下次完整恢复。" />
        <Checkbox checked={allowDirectStop} onChange={(event) => setAllowDirectStop(event.target.checked)}>
          Savepoint 停止失败，允许直接停止（不会生成 Savepoint，已有 Checkpoint 会保留）
        </Checkbox>
      </Space>
    </Modal>
  );
}
