package com.yjn.sqlagent.parsesql;

import java.util.Locale;
import java.util.Objects;

/** SQL 文本中的一个物理表引用。 */
public final class TableReference {
    private final String originalName;
    private final String name;
    private final String normalizedName;
    private final TableRole role;
    private final int startOffset;
    private final int endOffset;
    private final boolean dynamic;

    TableReference(String originalName, TableRole role, int startOffset, int endOffset) {
        this.originalName = originalName;
        this.name = cleanName(originalName);
        this.normalizedName = name.toLowerCase(Locale.ROOT);
        this.role = role;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.dynamic = originalName.contains("${") || originalName.contains("#{");
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public TableRole getRole() {
        return role;
    }

    public int getStartOffset() {
        return startOffset;
    }

    /** 源码区间的结束位置，采用左闭右开语义。 */
    public int getEndOffset() {
        return endOffset;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    static String normalize(String value) {
        return cleanName(value == null ? "" : value.trim()).toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean backtick = false;
        boolean doubleQuote = false;
        boolean singleQuote = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '`' && !doubleQuote && !singleQuote) {
                if (backtick && index + 1 < value.length() && value.charAt(index + 1) == '`') {
                    result.append('`');
                    index++;
                } else {
                    backtick = !backtick;
                }
            } else if (current == '"' && !backtick && !singleQuote) {
                if (doubleQuote && index + 1 < value.length() && value.charAt(index + 1) == '"') {
                    result.append('"');
                    index++;
                } else {
                    doubleQuote = !doubleQuote;
                }
            } else if (current == '\'' && !backtick && !doubleQuote) {
                if (singleQuote && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    result.append('\'');
                    index++;
                } else {
                    singleQuote = !singleQuote;
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof TableReference)) return false;
        TableReference other = (TableReference) value;
        return startOffset == other.startOffset && endOffset == other.endOffset
                && role == other.role && Objects.equals(normalizedName, other.normalizedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedName, role, startOffset, endOffset);
    }
}
