import type { SqlCommand } from '../types';

export const DEFAULT_SQL_COMMAND: SqlCommand = 'sql_generate';

export const SQL_COMMAND_LABELS: Record<SqlCommand, string> = {
  sql_generate: '/sql生成',
  sql_optimize: '/sql优化',
  sql_fix: '/sql修复',
  sql_explain: '/sql解释',
  sql_static_check: '/sql静态检查',
};

const PREFIX_TO_COMMAND: Record<string, SqlCommand> = Object.fromEntries(
  Object.entries(SQL_COMMAND_LABELS).map(([command, label]) => [label, command as SqlCommand]),
) as Record<string, SqlCommand>;

export function parseSqlCommandPrefix(input: string): {
  command: SqlCommand;
  message: string;
  matchedPrefix: string;
} {
  const trimmed = input.trimStart();
  for (const [prefix, command] of Object.entries(PREFIX_TO_COMMAND)) {
    if (trimmed === prefix || trimmed.startsWith(`${prefix} `) || trimmed.startsWith(`${prefix}\n`)) {
      return {
        command,
        message: trimmed.slice(prefix.length).trimStart(),
        matchedPrefix: prefix,
      };
    }
  }
  return { command: DEFAULT_SQL_COMMAND, message: input, matchedPrefix: '' };
}

export function commandLabel(command: SqlCommand): string {
  return SQL_COMMAND_LABELS[command] ?? SQL_COMMAND_LABELS[DEFAULT_SQL_COMMAND];
}
