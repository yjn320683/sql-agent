import { Alert, Empty, Spin, Tag, Tooltip } from 'antd';
import type { ReactNode } from 'react';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CodeOutlined,
  DatabaseOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import type { TaskQualityIssueVO, TaskQualityStatus, TaskQualityVO } from '../../types';

interface Props {
  quality?: TaskQualityVO;
  loading: boolean;
  error?: string;
}

const statusMeta: Record<TaskQualityStatus, { color: string; label: string }> = {
  PASSED: { color: 'success', label: '检查通过' },
  PASSED_WITH_WARNINGS: { color: 'warning', label: '通过，有风险项' },
  FAILED: { color: 'error', label: '检查未通过' },
  INCOMPLETE: { color: 'default', label: '检查不完整' },
};

const categoryLabels: Record<TaskQualityIssueVO['category'], string> = {
  correctness: '正确性',
  performance: '性能',
  metadata: '元数据',
  compilation: 'Hive 编译',
};

function CheckSource({ icon, title, detail, state }: {
  icon: ReactNode;
  title: string;
  detail: string;
  state: 'success' | 'warning' | 'error';
}) {
  return (
    <div className={`quality-source ${state}`}>
      {icon}
      <span><strong>{title}</strong><small>{detail}</small></span>
      {state === 'success' ? <CheckCircleOutlined /> : <ExclamationCircleOutlined />}
    </div>
  );
}

export default function TaskQualityPanel({ quality, loading, error }: Props) {
  if (loading) return <div className="workbench-tab-content centered"><Spin /><span>正在检查真实任务 SQL</span></div>;
  if (error) {
    return (
      <div className="workbench-tab-content centered">
        <Alert type="error" showIcon message="SQL 质量检查失败" description={error} />
      </div>
    );
  }
  if (!quality) {
    return (
      <div className="workbench-tab-content centered">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击工具栏中的质量检查，执行 AST、Metastore 与 Hive 编译校验" />
      </div>
    );
  }

  const status = statusMeta[quality.status];
  const compilation = quality.checks.compilation;
  const compilationState = compilation.valid === true
    ? 'success'
    : compilation.valid === false ? 'error' : 'warning';

  return (
    <div className="workbench-tab-content quality-result">
      <div className="quality-summary-bar">
        <Tag color={status.color}>{status.label}</Tag>
        <span><b>{quality.summary.error}</b> 错误</span>
        <span><b>{quality.summary.warning}</b> 风险</span>
        <span>{quality.checks.metadata.exists}/{quality.checks.metadata.checked} 个对象已验证</span>
        <Tooltip title={quality.source}><code>{quality.source}</code></Tooltip>
      </div>
      {!quality.complete ? (
        <Alert
          banner
          showIcon
          type="warning"
          message={`检查不完整：${[...quality.warnings, ...quality.missingReasons].join('；')}`}
        />
      ) : null}
      <div className="quality-source-row">
        <CheckSource
          icon={<CodeOutlined />}
          title="AST 规则"
          detail={quality.checks.static.passed ? '结构检查完成' : '发现阻断项'}
          state={quality.checks.static.passed ? 'success' : 'error'}
        />
        <CheckSource
          icon={<DatabaseOutlined />}
          title="Hive Metastore"
          detail={`${quality.checks.metadata.exists} 存在 · ${quality.checks.metadata.missing} 缺失 · ${quality.checks.metadata.unknown} 未知`}
          state={quality.checks.metadata.missing ? 'error' : quality.checks.metadata.unknown ? 'warning' : 'success'}
        />
        <CheckSource
          icon={<CheckCircleOutlined />}
          title="HiveServer2"
          detail={compilation.valid === true
            ? `编译通过 · ${compilation.compilationMs ?? 0} ms`
            : compilation.valid === false ? '编译失败' : '编译结果不可用'}
          state={compilationState}
        />
      </div>
      <div className="quality-issue-list">
        {quality.issues.length ? quality.issues.map((issue, index) => (
          <div className={`quality-issue ${issue.level}`} key={`${issue.code}-${issue.object || ''}-${index}`}>
            {issue.level === 'error' ? <CloseCircleOutlined /> : <ExclamationCircleOutlined />}
            <div>
              <div><Tag>{categoryLabels[issue.category]}</Tag><code>{issue.code}</code>{issue.object ? <span>{issue.object}</span> : null}</div>
              <strong>{issue.message}</strong>
              <p>{issue.suggestion}</p>
            </div>
          </div>
        )) : (
          <div className="quality-clean-result"><CheckCircleOutlined /> 未发现阻断项或风险项</div>
        )}
      </div>
    </div>
  );
}
