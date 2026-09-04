package com.yjn.sqlagent.realtime.service;

import com.yjn.sqlagent.realtime.repository.RealtimeTableRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class RealtimeTableService {
    private final RealtimeTableRepository repository;
    private final RealtimePaimonCatalogService catalog;

    public RealtimeTableService(RealtimeTableRepository repository, RealtimePaimonCatalogService catalog) {
        this.repository = repository; this.catalog = catalog;
    }

    public Map<String, Object> create(Map<String, Object> request, String actor) {
        String database = text(request.get("databaseName"));
        String tableName = text(request.get("tableName"));
        if (repository.findId(database, tableName) != null) {
            throw new IllegalStateException("实时表已登记：" + database + "." + tableName);
        }
        long id = repository.createDeclared(request, actor);
        try { repository.markPhysical(id, catalog.create(request), actor); }
        catch (RuntimeException ex) { repository.markError(id, ex, actor); throw ex; }
        return repository.required(id);
    }

    public Map<String, Object> refresh(long id, String actor) {
        Map<String, Object> table = repository.required(id);
        try {
            Map<String, Object> physical = catalog.describe(text(table.get("databaseName")), text(table.get("tableName")));
            repository.markPhysical(id, physical, actor); return repository.required(id);
        } catch (RuntimeException ex) { repository.markError(id, ex, actor); throw ex; }
    }

    public Map<String, Object> safeUpdate(long id, Map<String, Object> request, String actor) {
        Map<String, Object> table = repository.required(id);
        if ("sync".equals(table.get("creationSource")) && request.containsKey("addColumns")) {
            throw new IllegalStateException("同步任务维护的表结构只能由同步任务演进");
        }
        Map<String, Object> physical = catalog.safeAlter(text(table.get("databaseName")), text(table.get("tableName")), request);
        repository.updateFromPhysical(id, request, physical, actor); return repository.required(id);
    }

    public List<String> databases() { return catalog.databases(); }
    // 物理表发现是兜底对账，不应复用实例状态的 10 秒轮询周期，避免频繁打开 Hive Catalog。
    @Scheduled(initialDelayString = "${app.realtime.table-refresh-initial-delay-ms:60000}",
            fixedDelayString = "${app.realtime.table-refresh-delay-ms:300000}")
    public void discoverSyncTables() {
        List<Map<String,Object>> declared = repository.declaredSyncTables();
        if (declared.isEmpty()) return;
        try { catalog.describeExisting(declared).forEach((id, physical) -> repository.markPhysical(id, physical, "system")); }
        catch (RuntimeException ignored) { /* Catalog 暂时不可用时由下一轮重试。 */ }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
