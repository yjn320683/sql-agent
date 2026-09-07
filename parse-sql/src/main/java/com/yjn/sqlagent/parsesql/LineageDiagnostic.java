package com.yjn.sqlagent.parsesql;

/** 可定位到源码的解析或元数据诊断。 */
public final class LineageDiagnostic {
    private final String code;
    private final DiagnosticSeverity severity;
    private final String message;
    private final int line;
    private final int column;
    private final int startOffset;
    private final int endOffset;

    public LineageDiagnostic(String code, DiagnosticSeverity severity, String message,
                             int line, int column, int startOffset, int endOffset) {
        this.code = code; this.severity = severity; this.message = message;
        this.line = line; this.column = column; this.startOffset = startOffset; this.endOffset = endOffset;
    }
    public String getCode() { return code; }
    public DiagnosticSeverity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
}
