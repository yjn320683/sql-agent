package com.yjn.sqlagent.parsesql;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 规范化后的 catalog.database.table。 */
public final class TableIdentifier {
    private final String catalog;
    private final String database;
    private final String table;

    public TableIdentifier(String catalog, String database, String table) {
        this.catalog = clean(catalog);
        this.database = clean(database);
        this.table = clean(table);
        if (this.table.isEmpty()) throw new IllegalArgumentException("表名不能为空");
    }

    public String getCatalog() { return catalog; }
    public String getDatabase() { return database; }
    public String getTable() { return table; }

    public String qualifiedName() {
        if (!catalog.isEmpty()) return catalog + "." + database + "." + table;
        if (!database.isEmpty()) return database + "." + table;
        return table;
    }

    public String normalizedName() { return qualifiedName().toLowerCase(Locale.ROOT); }

    public static TableIdentifier parse(String raw, String defaultCatalog, String defaultDatabase) {
        List<String> parts = split(raw == null ? "" : raw.trim());
        if (parts.size() == 1) return new TableIdentifier(defaultCatalog, defaultDatabase, parts.get(0));
        if (parts.size() == 2) return new TableIdentifier(defaultCatalog, parts.get(0), parts.get(1));
        if (parts.size() == 3) return new TableIdentifier(parts.get(0), parts.get(1), parts.get(2));
        throw new IllegalArgumentException("表标识不正确：" + raw);
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int placeholderDepth = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                current.append(character);
                if (character == quote) {
                    if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                        current.append(value.charAt(++index));
                    } else quote = 0;
                }
                continue;
            }
            if (character == '`' || character == '"' || character == '\'') {
                quote = character; current.append(character); continue;
            }
            if ((character == '$' || character == '#') && index + 1 < value.length()
                    && value.charAt(index + 1) == '{') {
                placeholderDepth++; current.append(character); continue;
            }
            if (character == '}' && placeholderDepth > 0) {
                placeholderDepth--; current.append(character); continue;
            }
            if (character == '.' && placeholderDepth == 0) {
                parts.add(unquote(current.toString().trim())); current.setLength(0); continue;
            }
            current.append(character);
        }
        parts.add(unquote(current.toString().trim()));
        return parts;
    }

    private static String clean(String value) { return value == null ? "" : unquote(value.trim()); }

    static String unquote(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!quoted && (current == '`' || current == '"' || current == '\'')) {
                quoted = true; quote = current;
            } else if (quoted && current == quote) {
                if (i + 1 < value.length() && value.charAt(i + 1) == quote) { result.append(current); i++; }
                else quoted = false;
            } else result.append(current);
        }
        return result.toString();
    }

    @Override public boolean equals(Object value) {
        return value instanceof TableIdentifier
                && normalizedName().equals(((TableIdentifier) value).normalizedName());
    }
    @Override public int hashCode() { return Objects.hash(normalizedName()); }
    @Override public String toString() { return qualifiedName(); }
}
