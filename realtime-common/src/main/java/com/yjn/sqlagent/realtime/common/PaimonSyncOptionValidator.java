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
    }

    private static int integer(String value, String message) {
        if (!value.matches("-?\\d+")) throw new IllegalArgumentException(message);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(message, ex); }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
