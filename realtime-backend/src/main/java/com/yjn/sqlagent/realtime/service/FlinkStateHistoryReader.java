package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.config.RealtimeProperties;
import java.net.URI;
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
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;

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
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    boolean exists(long taskId, String type, String value) {
        String normalizedType = normalizedType(type);
        String root = taskRoot(taskId, normalizedType);
        String target = text(value);
        if (root.isEmpty() || target.isEmpty() || !inside(root, target)) return false;
        if (isRemote(root) || isRemote(target)) {
            try {
                return hadoopStateExists(root, target);
            } catch (java.io.FileNotFoundException ex) {
                return false;
            } catch (Exception ex) {
                throw new IllegalStateException("恢复点检查失败，请检查状态存储连接和权限", ex);
            }
        }
        try {
            Path path = localPath(target).toAbsolutePath().normalize();
            return Files.isDirectory(path) && Files.isRegularFile(path.resolve("_metadata"));
        } catch (Exception ex) {
            throw new IllegalStateException("恢复点检查失败，请检查状态存储连接和权限", ex);
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
        } catch (Exception ex) {
            throw new IllegalStateException("历史状态查询失败，请检查状态目录和权限", ex);
        }
        return result;
    }

    private List<Map<String, Object>> listRemote(String type, String root) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            org.apache.hadoop.fs.Path remoteRoot = new org.apache.hadoop.fs.Path(root);
            FileSystem fileSystem = remoteRoot.getFileSystem(hadoopConfiguration());
            if (!fileSystem.exists(remoteRoot)) return result;
            if ("checkpoint".equals(type)) {
                org.apache.hadoop.fs.RemoteIterator<org.apache.hadoop.fs.LocatedFileStatus> files =
                        fileSystem.listFiles(remoteRoot, true);
                while (files.hasNext()) {
                    FileStatus file = files.next();
                    org.apache.hadoop.fs.Path parent = file.getPath().getParent();
                    if ("_metadata".equals(file.getPath().getName())
                            && parent.getName().matches("chk-[0-9]+")) {
                        result.add(history(type, parent.toString(), file.getModificationTime()));
                    }
                }
            } else {
                for (FileStatus file : fileSystem.listStatus(remoteRoot)) {
                    if (file.isDirectory()) result.add(history(type,
                            file.getPath().toString(), file.getModificationTime()));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("历史状态查询失败，请检查 HDFS 连接和权限", ex);
        }
        return result;
    }

    private boolean validDirectory(String type, Path path) {
        Path name = path.getFileName();
        return name != null && validName(type, name.toString()) && Files.isRegularFile(path.resolve("_metadata"));
    }

    private boolean validName(String type, String name) {
        return "checkpoint".equals(type) ? name.matches("chk-[0-9]+") : name.startsWith("savepoint-");
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

    private boolean hadoopStateExists(String root, String target) throws Exception {
        Configuration configuration = hadoopConfiguration();
        org.apache.hadoop.fs.Path rootPath = new org.apache.hadoop.fs.Path(root);
        FileSystem rootFileSystem = rootPath.getFileSystem(configuration);
        URI qualifiedRoot = rootFileSystem.makeQualified(rootPath).toUri().normalize();
        org.apache.hadoop.fs.Path targetPath = new org.apache.hadoop.fs.Path(target);
        FileSystem targetFileSystem = targetPath.getFileSystem(configuration);
        URI qualifiedTarget = targetFileSystem.makeQualified(targetPath).toUri().normalize();
        if (!equal(qualifiedRoot.getScheme(), qualifiedTarget.getScheme())
                || !Objects.equals(authority(qualifiedRoot), authority(qualifiedTarget))) return false;
        String rootValue = trimSlash(qualifiedRoot.getPath());
        String targetValue = trimSlash(qualifiedTarget.getPath());
        if (targetValue.equals(rootValue) || !targetValue.startsWith(rootValue + "/")) return false;
        FileStatus status = targetFileSystem.getFileStatus(targetPath);
        return status.isDirectory()
                && targetFileSystem.isFile(new org.apache.hadoop.fs.Path(targetPath, "_metadata"));
    }

    private Configuration hadoopConfiguration() {
        Configuration configuration = new Configuration();
        String directory = text(System.getenv("HADOOP_CONF_DIR"));
        if (!directory.isEmpty()) {
            String root = trimSlash(directory);
            configuration.addResource(new org.apache.hadoop.fs.Path(root + "/core-site.xml"));
            configuration.addResource(new org.apache.hadoop.fs.Path(root + "/hdfs-site.xml"));
        }
        return configuration;
    }

    private String authority(URI value) {
        return value.getAuthority() == null ? "" : value.getAuthority().toLowerCase(Locale.ROOT);
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
