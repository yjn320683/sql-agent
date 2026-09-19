const STEP_MARKER = /^\s*====\s*step\s*:[^\r\n]*$/gim;

/**
 * 轻量 Hive SQL 格式化：只调整关键字与主子句换行，绝不改写注释、字符串、Step 标记或参数占位符。
 * 这里刻意不做 AST 重写，避免格式化动作改变待发布 SQL 的语义。
 */
export function formatHiveScript(script: string): string {
  const markers = [...script.matchAll(STEP_MARKER)];
  if (!markers.length) return formatStatement(script);
  const parts: string[] = [];
  markers.forEach((marker, index) => {
    const markerStart = marker.index || 0;
    if (index === 0 && markerStart > 0) parts.push(script.slice(0, markerStart).trimEnd());
    const sqlStart = markerStart + marker[0].length;
    const sqlEnd = markers[index + 1]?.index ?? script.length;
    parts.push(marker[0].trim(), formatStatement(script.slice(sqlStart, sqlEnd)));
  });
  return parts.filter(Boolean).join('\n');
}

function formatStatement(value: string): string {
  const lines = value.trim().split(/\r?\n/);
  return lines.map((line) => {
    if (/^\s*(--|\/\*)/.test(line)) return line.trimEnd();
    return line.trim()
      .replace(/\s+/g, ' ')
      .replace(/\s+(FROM|WHERE|GROUP\s+BY|HAVING|ORDER\s+BY|LIMIT|UNION(?:\s+ALL)?|INSERT\s+(?:INTO|OVERWRITE))\b/gi, '\n$1')
      .replace(/\s+((?:LEFT|RIGHT|FULL|INNER|CROSS)?\s*JOIN)\b/gi, '\n$1')
      .replace(/\b(SELECT|FROM|WHERE|GROUP\s+BY|HAVING|ORDER\s+BY|LIMIT|UNION(?:\s+ALL)?|INSERT\s+(?:INTO|OVERWRITE)|JOIN|ON|AS|AND|OR)\b/gi, (keyword) => keyword.toUpperCase());
  }).join('\n').replace(/\n{3,}/g, '\n\n').trim();
}
