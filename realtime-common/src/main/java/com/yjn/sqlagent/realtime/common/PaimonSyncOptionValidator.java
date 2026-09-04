package com.yjn.sqlagent.realtime.common;

import java.util.Map;

/** 同步 Action 支持的关键 Paimon Table 参数校验。 */
public final class PaimonSyncOptionValidator {
    private PaimonSyncOptionValidator() {
    }

    public static void validateTableConf(Map<String, ?> tableConf) {
        if (tableConf == null) return;
        if (tableConf.containsKey("bucket")) {
            String bucket = text(tableConf.get("bucket"));
            int value = integer(bucket, "Bucket 只允许 -2、-1 或正整数");
            if (value < -2 || value == 0) throw new IllegalArgumentException("Bucket 只允许 -2、-1 或正整数");
        }
        if (tableConf.containsKey("sink.parallelism")) {
            String parallelism = text(tableConf.get("sink.parallelism"));
            if (integer(parallelism, "Sink 并行度必须为正整数") <= 0) {
                throw new IllegalArgumentException("Sink 并行度必须为正整数");
            }
        }
        validateOptionRelations(tableConf);
    }

    private static void validateOptionRelations(Map<String, ?> tableConf) {
        String producer = text(tableConf.get("changelog-producer"));
        String rowDeduplicate = text(tableConf.get("changelog-producer.row-deduplicate"));
        boolean hasIgnoreFields = tableConf.containsKey("changelog-producer.row-deduplicate-ignore-fields");
        if (("true".equalsIgnoreCase(rowDeduplicate) || hasIgnoreFields)
                && !("lookup".equals(producer) || "full-compaction".equals(producer))) {
            throw new IllegalArgumentException("Changelog 行去重仅支持 lookup 或 full-compaction Producer");
        }
        if (hasIgnoreFields && !"true".equalsIgnoreCase(rowDeduplicate)) {
            throw new IllegalArgumentException("Changelog 去重忽略字段依赖启用 Changelog 行去重");
        }
        String mergeEngine = text(tableConf.get("merge-engine"));
        if ("first-row".equals(mergeEngine)) {
            throw new IllegalArgumentException("MySQL CDC 暂不支持 first-row：该模式不能处理 UPDATE_BEFORE 或 DELETE 记录");
        }
        if ("partial-update".equals(mergeEngine)) {
            throw new IllegalArgumentException("MySQL CDC 暂不支持 partial-update：源表 DELETE 会导致作业失败，当前未提供删除策略配置");
        }
        if ("aggregation".equals(mergeEngine)) {
            throw new IllegalArgumentException("MySQL CDC 暂不支持 aggregation：源表 DELETE 会在目标保留空值记录，造成数据不一致");
        }
    }

    private static int integer(String value, String message) {
        if (!value.matches("-?\\d+")) throw new IllegalArgumentException(message);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(message, ex); }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
