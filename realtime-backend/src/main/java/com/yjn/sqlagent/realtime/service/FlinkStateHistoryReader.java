package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 读取当前同步任务可用的 Flink Checkpoint/Savepoint，避免展示已经失效的恢复点。 */
final class FlinkStateHistoryReader {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RealtimeProperties properties;

    FlinkStateHistoryReader(RealtimeProperties properties) {
        this.properties = properties;
    }

    List<Map<String, Object>> list(long taskId, String type) {
        String normalizedType = normalizedType(type);
        String root = taskRoot(taskId, normalizedType);
        if (root.isEmpty()) return new ArrayList<>();
        List<Map<String, Object>> result = isRemote(root)
                ? listRemote(normalizedType, root) : listLocal(normalizedType, root);
        result.sort(Comparator.comparing((Map<String, Object> item) -> text(item.get("createTime")),
                Comparator.nullsLast(String::compareTo)).reversed());
        return result;
    }

    boolean exists(long taskId, String type, String value) {
        String normalizedType = normalizedType(type);
        String root = taskRoot(taskId, normalizedType);
        String target = text(value);
        if (root.isEmpty() || target.isEmpty() || !inside(root, target)) return false;
        if (isRemote(root) || isRemote(target)) {
            return commandSucceeded(List.of(properties.getHadoopBin(), "fs", "-test", "-d", target))
                    && commandSucceeded(List.of(properties.getHadoopBin(), "fs", "-test", "-e", trimSlash(target) + "/_metadata"));
        }
        try {
            Path path = localPath(target).toAbsolutePath().normalize();
            return Files.isDirectory(path) && Files.isRegularFile(path.resolve("_metadata"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private List<Map<String, Object>> listLocal(String type, String value) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Path root = localPath(value);
            if (!Files.isDirectory(root)) return result;
            try (Stream<Path> stream = Files.walk(root, "checkpoint".equals(type) ? 4 : 2)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> validDirectory(type, path))
                        .forEach(path -> result.add(history(type, path.toString(), modified(path.resolve("_metadata")))));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private List<Map<String, Object>> listRemote(String type, String root) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Process process = null;
        try {
            process = new ProcessBuilder(properties.getHadoopBin(), "fs", "-ls", "-R", root)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ArrayList<>();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 8 || parts[0].startsWith("d")) continue;
                    String metadata = parts[parts.length - 1];
                    if (!metadata.endsWith("/_metadata")) continue;
                    String path = metadata.substring(0, metadata.length() - "/_metadata".length());
                    if (!validName(type, path.substring(path.lastIndexOf('/') + 1))) continue;
                    result.put(path, history(type, path, parts[5] + " " + parts[6]));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        } finally {
            if (process != null) process.destroy();
        }
        return new ArrayList<>(result.values());
    }

    private boolean validDirectory(String type, Path path) {
        Path name = path.getFileName();
        return name != null && validName(type, name.toString()) && Files.isRegularFile(path.resolve("_metadata"));
    }

    private boolean validName(String type, String name) {
        return "checkpoint".equals(type) ? name.startsWith("chk-") : name.startsWith("savepoint-");
    }

    private Map<String, Object> history(String type, String path, long modifiedMillis) {
        String time = modifiedMillis <= 0 ? "" : TIME_FORMAT.format(
                Instant.ofEpochMilli(modifiedMillis).atZone(ZoneId.systemDefault()).toLocalDateTime());
        return history(type, path, time);
    }

    private Map<String, Object> history(String type, String path, String createTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        String name = path.substring(path.lastIndexOf('/') + 1);
        result.put("type", type); result.put("path", path); result.put("createTime", createTime);
        result.put("label", createTime.isEmpty() ? name : createTime + " (" + name + ")");
        return result;
    }

    private long modified(Path path) {
        try { FileTime value = Files.getLastModifiedTime(path); return value.toMillis(); }
        catch (Exception ignored) { return 0L; }
    }

    private boolean commandSucceeded(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private String taskRoot(long taskId, String type) {
        String root = "checkpoint".equals(type) ? text(properties.getCheckpointDir()) : text(properties.getSavepointDir());
        return root.isEmpty() ? "" : trimSlash(root) + "/task-" + taskId;
    }

    private String normalizedType(String type) {
        String value = text(type).toLowerCase(Locale.ROOT);
        if (!List.of("checkpoint", "savepoint").contains(value)) {
            throw new IllegalArgumentException("历史状态类型必须是 checkpoint 或 savepoint");
        }
        return value;
    }

    private boolean inside(String root, String value) {
        try {
            if (isRemote(root) || isRemote(value)) {
                URI rootUri = URI.create(trimSlash(root)).normalize();
                URI valueUri = URI.create(trimSlash(value)).normalize();
                return equal(rootUri.getScheme(), valueUri.getScheme())
                        && equal(rootUri.getAuthority(), valueUri.getAuthority())
                        && !trimSlash(valueUri.getPath()).equals(trimSlash(rootUri.getPath()))
                        && trimSlash(valueUri.getPath()).startsWith(trimSlash(rootUri.getPath()) + "/");
            }
            Path rootPath = localPath(root).toAbsolutePath().normalize();
            Path valuePath = localPath(value).toAbsolutePath().normalize();
            return !valuePath.equals(rootPath) && valuePath.startsWith(rootPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean equal(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private boolean isRemote(String value) { return value.startsWith("hdfs://"); }
    private Path localPath(String value) { return value.startsWith("file://") ? Path.of(URI.create(value)) : Path.of(value); }
    private String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
