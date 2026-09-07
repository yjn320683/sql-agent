package com.yjn.sqlagent.parsesql;

/** SQL 无法完整解析时抛出，包含首个错误的源码位置。 */
public class SqlParseException extends IllegalArgumentException {
    private final int line;
    private final int column;
    private final int startOffset;
    private final int endOffset;

    public SqlParseException(String message, int line, int column) {
        this(message, line, column, -1, -1);
    }

    public SqlParseException(String message, int line, int column, int startOffset, int endOffset) {
        super(message);
        this.line = line;
        this.column = column;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
}
