import { useState } from 'react';
import { Button, Input, Typography, message as antdMessage } from 'antd';
import { LoginOutlined } from '@ant-design/icons';
import { login } from '../api/auth';
import type { LoginUser } from '../types';

interface Props {
  onLoggedIn: (user: LoginUser) => void;
}

export default function LoginPage({ onLoggedIn }: Props) {
  const [obId, setObId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleLogin = async () => {
    const value = obId.trim();
    if (!/^[0-9]{1,20}$/.test(value)) {
      antdMessage.warning('请输入 1-20 位数字 obId');
      return;
    }
    setSubmitting(true);
    try {
      onLoggedIn(await login(value));
    } catch (error) {
      antdMessage.error(`登录失败：${(error as Error).message}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-mark">DM</div>
        <Typography.Title level={2}>SQL Agent</Typography.Title>
        <Typography.Paragraph type="secondary">
          输入 obId 建立本地调试会话。
        </Typography.Paragraph>
        <Input
          size="large"
          value={obId}
          inputMode="numeric"
          maxLength={20}
          autoFocus
          placeholder="请输入 obId"
          onChange={(event) => setObId(event.target.value)}
          onPressEnter={handleLogin}
        />
        <Button
          type="primary"
          size="large"
          block
          icon={<LoginOutlined />}
          loading={submitting}
          onClick={handleLogin}
        >
          登录
        </Button>
      </div>
    </div>
  );
}
