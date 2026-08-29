package com.yjn.sqlagent.datacompare.service;

import com.yjn.sqlagent.datacompare.config.DataCompareProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CompareLog {
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final String TRUNCATED = "[TRUNCATED] 日志已达到 50MB 上限，后续内容不再写入";
    private static final Pattern URI_USER = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern SECRET = Pattern.compile("(?i)(password|token|secret)\\s*[=:]\\s*[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private final Path root;

    public CompareLog(DataCompareProperties properties) {
        this.root = Paths.get(properties.getLogDir()).toAbsolutePath().normalize();
    }

    public String path(long jobId, long tableId) {
        return root.resolve(jobId + "_" + tableId + ".log").toString();
    }

    public synchronized void write(String path, String message) {
        try {
            Path target = Paths.get(path).toAbsolutePath().normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("非法日志路径");
            Files.createDirectories(root);
            if (Files.exists(target) && Files.size(target) >= MAX_BYTES) return;
            String safe = SECRET.matcher(URI_USER.matcher(message).replaceAll("$1<redacted>@"))
                    .replaceAll("$1=<redacted>");
            byte[] line = (LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    + " " + safe + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            long current = Files.exists(target) ? Files.size(target) : 0;
            if (current + line.length > MAX_BYTES) {
                line = (LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        + " " + TRUNCATED + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            }
            Files.write(target, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("写入验数日志失败", e);
        }
    }

    public String read(String path, int maxChars) {
        try {
            Path target = Paths.get(path).toAbsolutePath().normalize();
            if (!target.startsWith(root) || !Files.exists(target)) return "";
            String value = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            return value.length() <= maxChars ? value : value.substring(value.length() - maxChars);
        } catch (IOException e) {
            throw new IllegalStateException("读取验数日志失败", e);
        }
    }
}
