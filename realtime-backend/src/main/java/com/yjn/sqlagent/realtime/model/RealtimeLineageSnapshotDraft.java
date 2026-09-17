package com.yjn.sqlagent.realtime.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 进入事务前生成的实时任务血缘快照草稿，不包含 SQL 或密码正文。 */
public final class RealtimeLineageSnapshotDraft {
    private final String checksum;
    private final String dialect;
    private final String defaultDatabase;
    private final boolean complete;
    private final Map<String, Object> lineage;
    private final List<Map<String, Object>> diagnostics;

    public RealtimeLineageSnapshotDraft(String checksum, String dialect, String defaultDatabase,
                                        boolean complete, Map<String, Object> lineage,
                                        List<Map<String, Object>> diagnostics) {
        this.checksum = checksum;
        this.dialect = dialect;
        this.defaultDatabase = defaultDatabase;
        this.complete = complete;
        this.lineage = Collections.unmodifiableMap(new LinkedHashMap<>(lineage));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    public String getChecksum() { return checksum; }
    public String getDialect() { return dialect; }
    public String getDefaultDatabase() { return defaultDatabase; }
    public boolean isComplete() { return complete; }
    public Map<String, Object> getLineage() { return lineage; }
    public List<Map<String, Object>> getDiagnostics() { return diagnostics; }
}
