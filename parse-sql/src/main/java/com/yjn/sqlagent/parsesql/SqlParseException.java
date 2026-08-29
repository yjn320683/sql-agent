package com.yjn.sqlagent.parsesql;

/** Hive SQL 无法按参考 grammar 完整解析时抛出。 */
public class SqlParseException extends IllegalArgumentException {
    private final int line;
    private final int column;

    public SqlParseException(String message, int line, int column) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }
}
