import type { ReactNode } from 'react';
import { Button, Modal, Tooltip } from 'antd';
import { CheckOutlined, CopyOutlined } from '@ant-design/icons';

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; lines: string[] }
  | { kind: 'code'; language: string; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'table'; header: string[]; rows: string[][] }
  | { kind: 'hr' };

interface Props {
  text: string;
  streaming?: boolean;
  onApplySql?: (sql: string) => void;
  taskName?: string;
}

export default function MarkdownContent({ text, streaming, onApplySql, taskName }: Props) {
  const blocks = parseBlocks(text);
  return (
    <div className="markdown-content">
      {blocks.map((block, index) => renderBlock(block, index, onApplySql, taskName))}
      {streaming ? <span className="cursor">▋</span> : null}
    </div>
  );
}

function parseBlocks(text: string): Block[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const blocks: Block[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }

    const fence = line.match(/^```\s*([^`]*)\s*$/);
    if (fence) {
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !/^```\s*$/.test(lines[index])) {
        codeLines.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) index += 1;
      blocks.push({ kind: 'code', language: fence[1].trim(), text: codeLines.join('\n') });
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      blocks.push({ kind: 'heading', level: heading[1].length, text: heading[2].trim() });
      index += 1;
      continue;
    }

    if (/^\s*(-{3,}|_{3,}|\*{3,})\s*$/.test(line)) {
      blocks.push({ kind: 'hr' });
      index += 1;
      continue;
    }

    if (isTableStart(lines, index)) {
      const tableLines = [lines[index], lines[index + 1]];
      index += 2;
      while (index < lines.length && isTableRow(lines[index])) {
        tableLines.push(lines[index]);
        index += 1;
      }
      blocks.push(parseTable(tableLines));
      continue;
    }

    const unordered = line.match(/^\s*[-*+]\s+(.+)$/);
    const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
    if (unordered || ordered) {
      const isOrdered = Boolean(ordered);
      const items: string[] = [];
      while (index < lines.length) {
        const match = isOrdered
          ? lines[index].match(/^\s*\d+[.)]\s+(.+)$/)
          : lines[index].match(/^\s*[-*+]\s+(.+)$/);
        if (!match) break;
        items.push(match[1].trim());
        index += 1;
      }
      blocks.push({ kind: 'list', ordered: isOrdered, items });
      continue;
    }

    const paragraphLines: string[] = [];
    while (index < lines.length && lines[index].trim() && !isSpecialStart(lines, index)) {
      paragraphLines.push(lines[index]);
      index += 1;
    }
    blocks.push({ kind: 'paragraph', lines: paragraphLines });
  }

  return blocks;
}

function isSpecialStart(lines: string[], index: number): boolean {
  const line = lines[index];
  return (
    /^```/.test(line) ||
    /^(#{1,6})\s+/.test(line) ||
    /^\s*(-{3,}|_{3,}|\*{3,})\s*$/.test(line) ||
    /^\s*[-*+]\s+/.test(line) ||
    /^\s*\d+[.)]\s+/.test(line) ||
    isTableStart(lines, index)
  );
}

function isTableStart(lines: string[], index: number): boolean {
  return Boolean(lines[index + 1] && isTableRow(lines[index]) && isTableSeparator(lines[index + 1]));
}

function isTableRow(line: string): boolean {
  return line.includes('|') && line.trim().split('|').filter(Boolean).length >= 2;
}

function isTableSeparator(line: string): boolean {
  const cells = splitTableLine(line);
  return cells.length >= 2 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.trim()));
}

function parseTable(lines: string[]): Extract<Block, { kind: 'table' }> {
  return {
    kind: 'table',
    header: splitTableLine(lines[0]),
    rows: lines.slice(2).map(splitTableLine),
  };
}

function splitTableLine(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim());
}

function renderBlock(block: Block, index: number, onApplySql?: (sql: string) => void, taskName?: string): ReactNode {
  if (block.kind === 'heading') {
    const Tag = `h${Math.min(block.level, 4)}` as 'h1' | 'h2' | 'h3' | 'h4';
    return <Tag key={index}>{renderInline(block.text)}</Tag>;
  }

  if (block.kind === 'paragraph') {
    return (
      <p key={index}>
        {block.lines.map((line, lineIndex) => (
          <span key={lineIndex}>
            {lineIndex > 0 ? <br /> : null}
            {renderInline(line)}
          </span>
        ))}
      </p>
    );
  }

  if (block.kind === 'code') {
    const isSql = ['sql', 'hive', 'hql'].includes(block.language.toLowerCase());
    return (
      <div key={index} className="markdown-code-wrap">
        <div className="markdown-code-toolbar"><span>{block.language || 'code'}</span><span>
          <Tooltip title="复制"><Button type="text" size="small" icon={<CopyOutlined />} onClick={() => void navigator.clipboard.writeText(block.text)} /></Tooltip>
          {isSql && onApplySql ? <Tooltip title={`应用到${taskName || '当前任务'}`}><Button type="text" size="small" icon={<CheckOutlined />} onClick={() => Modal.confirm({ title: `覆盖${taskName || '当前任务'}的 SQL？`, content: '任务名称和描述保持不变，SQL 将替换为当前代码块。', okText: '确认应用', cancelText: '取消', onOk: () => onApplySql(block.text) })} /></Tooltip> : null}
        </span></div>
        <pre className="markdown-code"><code>{block.text}</code></pre>
      </div>
    );
  }

  if (block.kind === 'list') {
    const Tag = block.ordered ? 'ol' : 'ul';
    return (
      <Tag key={index}>
        {block.items.map((item, itemIndex) => (
          <li key={itemIndex}>{renderInline(item)}</li>
        ))}
      </Tag>
    );
  }

  if (block.kind === 'table') {
    return (
      <div key={index} className="markdown-table-wrap">
        <table>
          <thead>
            <tr>
              {block.header.map((cell, cellIndex) => (
                <th key={cellIndex}>{renderInline(cell)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {block.rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {block.header.map((_, cellIndex) => (
                  <td key={cellIndex}>{renderInline(row[cellIndex] ?? '')}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return <hr key={index} />;
}

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let index = 0;

  while (index < text.length) {
    if (text.startsWith('`', index)) {
      const end = text.indexOf('`', index + 1);
      if (end !== -1) {
        nodes.push(<code key={nodes.length}>{text.slice(index + 1, end)}</code>);
        index = end + 1;
        continue;
      }
    }

    if (text.startsWith('**', index)) {
      const end = text.indexOf('**', index + 2);
      if (end !== -1) {
        nodes.push(<strong key={nodes.length}>{renderInline(text.slice(index + 2, end))}</strong>);
        index = end + 2;
        continue;
      }
    }

    const nextCode = text.indexOf('`', index + 1);
    const nextStrong = text.indexOf('**', index + 1);
    const candidates = [nextCode, nextStrong].filter((pos) => pos !== -1);
    const next = candidates.length ? Math.min(...candidates) : text.length;
    nodes.push(text.slice(index, next));
    index = next;
  }

  return nodes;
}
