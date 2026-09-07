package com.yjn.sqlagent.parsesql;

/** 一次解析所需的 SQL、方言和元数据上下文。 */
public final class ParseRequest {
    private final String sql;
    private final SqlDialect dialect;
    private final String defaultCatalog;
    private final String defaultDatabase;
    private final TableMetadataProvider metadataProvider;
    private final ParseMode mode;

    private ParseRequest(Builder builder) {
        this.sql = builder.sql == null ? "" : builder.sql;
        this.dialect = builder.dialect;
        this.defaultCatalog = builder.defaultCatalog;
        this.defaultDatabase = builder.defaultDatabase;
        this.metadataProvider = builder.metadataProvider;
        this.mode = builder.mode;
    }

    public static Builder builder(String sql) { return new Builder(sql); }
    public String getSql() { return sql; }
    public SqlDialect getDialect() { return dialect; }
    public String getDefaultCatalog() { return defaultCatalog; }
    public String getDefaultDatabase() { return defaultDatabase; }
    public TableMetadataProvider getMetadataProvider() { return metadataProvider; }
    public ParseMode getMode() { return mode; }

    public Builder toBuilder(String nextSql) {
        return builder(nextSql).dialect(dialect).defaultCatalog(defaultCatalog)
                .defaultDatabase(defaultDatabase).metadataProvider(metadataProvider).mode(mode);
    }

    public static final class Builder {
        private final String sql;
        private SqlDialect dialect = SqlDialect.HIVE;
        private String defaultCatalog = "";
        private String defaultDatabase = "default";
        private TableMetadataProvider metadataProvider = TableMetadataProvider.NONE;
        private ParseMode mode = ParseMode.STRICT;

        private Builder(String sql) { this.sql = sql; }
        public Builder dialect(SqlDialect value) { this.dialect = value == null ? SqlDialect.HIVE : value; return this; }
        public Builder defaultCatalog(String value) { this.defaultCatalog = value == null ? "" : value.trim(); return this; }
        public Builder defaultDatabase(String value) { this.defaultDatabase = value == null || value.trim().isEmpty() ? "default" : value.trim(); return this; }
        public Builder metadataProvider(TableMetadataProvider value) { this.metadataProvider = value == null ? TableMetadataProvider.NONE : value; return this; }
        public Builder mode(ParseMode value) { this.mode = value == null ? ParseMode.STRICT : value; return this; }
        public ParseRequest build() { return new ParseRequest(this); }
    }
}
