type Token = { value: string; kind: 'space' | 'word' | 'literal' | 'comment' | 'symbol' };

/** Format layout only: never rewrite SQL words, quoted values, or comments. */
export function formatFlinkSql(source: string): string {
  const tokens = tokenize(source);
  const output: string[] = [];
  let line = '';
  let indent = 0;
  let caseDepth = 0;
  let pendingSpace = false;
  let afterComma = false;
  let afterLineComment = false;
  let previous: Token | undefined;

  const flush = () => {
    if (line.trim()) output.push(`${'  '.repeat(indent)}${line.trimEnd()}`);
    line = '';
    pendingSpace = false;
    afterComma = false;
  };

  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (token.kind === 'space') {
      pendingSpace = true;
      continue;
    }
    if (afterLineComment) {
      flush();
      afterLineComment = false;
    }
    const word = token.kind === 'word' ? token.value.toUpperCase() : '';
    let nextIndex = index + 1;
    while (tokens[nextIndex]?.kind === 'space') nextIndex += 1;
    const next = tokens[nextIndex];
    const nextWord = next?.kind === 'word' ? next.value.toUpperCase() : '';
    const previousWord = previous?.kind === 'word' ? previous.value.toUpperCase() : '';
    const startsClause = word === 'SELECT' || word === 'FROM' || word === 'WHERE'
      || word === 'HAVING' || word === 'LIMIT' || word === 'UNION'
      || word === 'INSERT' || word === 'EXECUTE' || word === 'BEGIN'
      || word === 'GROUP' && nextWord === 'BY'
      || word === 'ORDER' && nextWord === 'BY'
      || ['LEFT', 'RIGHT', 'FULL', 'INNER', 'CROSS'].includes(word) && nextWord === 'JOIN'
      || word === 'JOIN' && !['LEFT', 'RIGHT', 'FULL', 'INNER', 'CROSS'].includes(previousWord)
      || word === 'ON';
    const closesCase = word === 'END' && caseDepth > 0;
    if (word === 'CASE') caseDepth += 1;
    if (closesCase) caseDepth -= 1;
    if (word === 'END' && !closesCase && line.trim()) flush();
    if (word === 'END' && !closesCase) indent = Math.max(0, indent - 1);
    else if (startsClause && line.trim()) flush();

    if (line && (pendingSpace || afterComma || token.kind === 'comment' && token.value.startsWith('--'))) {
      line += ' ';
    }
    line += token.value;
    pendingSpace = false;
    afterComma = token.value === ',';

    if (token.value === ';') flush();
    else if (word === 'BEGIN') { flush(); indent += 1; }
    else if (token.kind === 'comment' && token.value.startsWith('--')) afterLineComment = true;
    else if (token.kind === 'comment' && /\r|\n/.test(token.value)) flush();
    previous = token;
  }
  flush();
  return output.join('\n');
}

function tokenize(source: string): Token[] {
  const tokens: Token[] = [];
  for (let index = 0; index < source.length;) {
    const start = index;
    const char = source[index];
    if (/\s/.test(char)) {
      while (index < source.length && /\s/.test(source[index])) index += 1;
      tokens.push({ value: source.slice(start, index), kind: 'space' });
    } else if (source.startsWith('--', index)) {
      index += 2;
      while (index < source.length && !/[\r\n]/.test(source[index])) index += 1;
      tokens.push({ value: source.slice(start, index), kind: 'comment' });
    } else if (source.startsWith('/*', index)) {
      const end = source.indexOf('*/', index + 2);
      index = end < 0 ? source.length : end + 2;
      tokens.push({ value: source.slice(start, index), kind: 'comment' });
    } else if (char === "'" || char === '"' || char === '`') {
      index += 1;
      while (index < source.length) {
        if (source[index] === '\\') { index = Math.min(source.length, index + 2); continue; }
        if (source[index] === char) {
          index += 1;
          if (source[index] === char) { index += 1; continue; }
          break;
        }
        index += 1;
      }
      tokens.push({ value: source.slice(start, index), kind: 'literal' });
    } else if (/[a-zA-Z_]/.test(char)) {
      index += 1;
      while (index < source.length && /[\w$]/.test(source[index])) index += 1;
      tokens.push({ value: source.slice(start, index), kind: 'word' });
    } else {
      index += 1;
      tokens.push({ value: char, kind: 'symbol' });
    }
  }
  return tokens;
}
