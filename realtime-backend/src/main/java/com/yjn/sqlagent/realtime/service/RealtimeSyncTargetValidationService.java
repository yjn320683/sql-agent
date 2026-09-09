package com.yjn.sqlagent.realtime.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 校验同步任务新增的目标 Paimon 物理表没有被已有数据占用。 */
@Service
public class RealtimeSyncTargetValidationService {
    private final RealtimePaimonCatalogService catalog;

    public RealtimeSyncTargetValidationService(RealtimePaimonCatalogService catalog) {
        this.catalog = catalog;
    }

    /** 仅检查相对当前保存配置新增的源表，保留表不会被重复拦截。 */
    public void validateAddedTargets(Map<String, Object> persistedConfig,
            Map<String, Object> requestedConfig) {
        if (requestedConfig == null) return;
        Map<String, Object> requestedCdc = map(requestedConfig.get("cdcConfig"));
        List<String> requestedSources = strings(requestedCdc.get("selectedTables"));
        Set<String> persistedSources = new LinkedHashSet<>(strings(
                map(persistedConfig == null ? null : persistedConfig.get("cdcConfig"))
                        .get("selectedTables")));
        List<String> requestedTargets = targetTables(requestedCdc);
        if (requestedSources.size() != requestedTargets.size()) {
            throw new IllegalArgumentException("源表列表与目标Paimon表列表不一致");
        }

        List<Integer> addedIndexes = new ArrayList<>();
        List<String> addedTargets = new ArrayList<>();
        for (int index = 0; index < requestedSources.size(); index++) {
            if (!persistedSources.contains(requestedSources.get(index))) {
                addedIndexes.add(index);
                addedTargets.add(requestedTargets.get(index));
            }
        }
        if (addedIndexes.isEmpty()) return;

        String database = first(requestedConfig.get("targetDatabase"), requestedCdc.get("targetDatabase"));
        if (database.isEmpty()) throw new IllegalArgumentException("目标 Paimon 库不能为空");
        Set<String> existing = catalog.existingTables(database, addedTargets);
        List<String> conflicts = new ArrayList<>();
        for (Integer index : addedIndexes) {
            String target = requestedTargets.get(index);
            if (existing.contains(target)) {
                conflicts.add(requestedSources.get(index) + " → " + database + "." + target);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("新增同步表失败：以下源表对应的目标 Paimon 表已存在，系统不会覆盖已有表："
                    + String.join("；", conflicts) + "。请确认目标表归属并处理后重试");
        }
    }

    private List<String> targetTables(Map<String, Object> cdc) {
        List<String> configured = strings(cdc.get("targetTableList"));
        if (!configured.isEmpty()) return configured;
        String single = text(cdc.get("targetTable"));
        if (!single.isEmpty()) return List.of(single);
        String prefix = text(cdc.get("tablePrefix"));
        String suffix = text(cdc.get("tableSuffix"));
        List<String> result = new ArrayList<>();
        for (String source : strings(cdc.get("selectedTables"))) result.add(prefix + source + suffix);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) if (!text(item).isEmpty()) result.add(text(item));
        } else if (!text(value).isEmpty()) {
            for (String item : text(value).split(",")) if (!item.trim().isEmpty()) result.add(item.trim());
        }
        return result;
    }

    private String first(Object... values) {
        for (Object value : values) if (!text(value).isEmpty()) return text(value);
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
