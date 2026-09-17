package com.yjn.sqlagent.datamap.project;

import java.util.Map;

/** 允许业务模块使用已保存配置补充非 SQL 任务的确定性字段映射。 */
public interface LineageFactEnricher {
    boolean supports(String taskScope, String taskType);
    Map<String, Object> enrich(long taskId, Long versionId, int versionNo, Map<String, Object> facts);
}
