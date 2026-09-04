import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, message } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './styles.css';

// 并发接口失败时只保留一条全局提示，避免登录失效等同一错误叠成通知墙。
message.config({ maxCount: 1 });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1769e0',
          colorText: '#172033',
          colorTextSecondary: '#667085',
          colorBorder: '#dfe3e8',
          borderRadius: 6,
          fontSize: 13,
          fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
        },
        components: {
          Button: { controlHeight: 32 },
          Input: { controlHeight: 32 },
          Table: {
            headerBg: '#f8f9fb',
            headerColor: '#667085',
            headerSplitColor: '#e8ebef',
            rowHoverBg: '#f7faff',
            cellPaddingBlock: 7,
            cellPaddingBlockMD: 7,
            cellPaddingBlockSM: 7,
            cellPaddingInline: 12,
            cellPaddingInlineMD: 12,
            cellPaddingInlineSM: 12,
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
