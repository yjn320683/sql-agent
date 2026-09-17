package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.model.dto.SqlCompletionRequestDTO;
import com.yjn.sqlagent.model.dto.SqlStructurePreviewDTO;
import com.yjn.sqlagent.model.dto.SqlQueryPreviewDTO;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.service.AgentProxyService;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.PlatformHealthService;
import com.yjn.sqlagent.service.TaskVersionCheckService;
import com.yjn.sqlagent.service.SqlQueryPreviewService;
import com.yjn.sqlagent.service.HiveFunctionCatalogService;
import com.yjn.sqlagent.datacompare.service.HiveDdlService;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final AgentProxyService agentProxyService;
    private final CurrentUserService currentUserService;
    private final PlatformHealthService platformHealthService;
    private final TaskVersionCheckService taskVersionCheckService;
    private final SqlQueryPreviewService sqlQueryPreviewService;
    private final HiveFunctionCatalogService hiveFunctionCatalogService;
    private final HiveDdlService hiveDdlService;

    public WorkspaceController(AgentProxyService agentProxyService,
                               CurrentUserService currentUserService,
                               PlatformHealthService platformHealthService,
                               TaskVersionCheckService taskVersionCheckService,
                               SqlQueryPreviewService sqlQueryPreviewService,
                               HiveFunctionCatalogService hiveFunctionCatalogService,
                               HiveDdlService hiveDdlService) {
        this.agentProxyService = agentProxyService;
        this.currentUserService = currentUserService;
        this.platformHealthService = platformHealthService;
        this.taskVersionCheckService = taskVersionCheckService;
        this.sqlQueryPreviewService = sqlQueryPreviewService;
        this.hiveFunctionCatalogService = hiveFunctionCatalogService;
        this.hiveDdlService = hiveDdlService;
    }

    @GetMapping("/hive/databases")
    public BaseResponse<Map<String, Object>> databases(HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.listHiveDatabases());
    }

    @GetMapping("/hive/functions")
    public BaseResponse<Map<String, Object>> functions(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String defaultDb,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (limit < 1 || limit > 200 || offset < 0 || keyword.length() > 256) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "函数检索参数非法");
        }
        return BaseResponse.success(hiveFunctionCatalogService.search(keyword, limit, offset, defaultDb));
    }

    @GetMapping("/hive/functions/{name}")
    public BaseResponse<Map<String, Object>> function(
            @PathVariable String name,
            @RequestParam(required = false) String defaultDb,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(hiveFunctionCatalogService.detail(name, defaultDb));
    }

    @GetMapping("/platform/health")
    public BaseResponse<Map<String, Object>> platformHealth(HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(platformHealthService.health());
    }

    @GetMapping("/data-map/tables/{db}/{table}/primary-keys")
    public BaseResponse<Map<String, Object>> dataMapPrimaryKeys(
            @PathVariable String db,
            @PathVariable String table,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.getDataMapPrimaryKeys(db, table));
    }

    @GetMapping("/task-executions/{executionId}/diagnostics")
    public BaseResponse<Map<String, Object>> taskExecutionDiagnostics(
            @PathVariable long executionId,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (executionId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "实例 ID 非法");
        }
        return BaseResponse.success(agentProxyService.getTaskExecutionDiagnostics(executionId));
    }

    @GetMapping("/tasks/{taskId}/lineage")
    public BaseResponse<Map<String, Object>> taskLineage(
            @PathVariable long taskId,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) String defaultDb,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.getTaskLineage(taskId, versionNo, defaultDb));
    }

    @GetMapping("/tasks/{taskId}/dependencies")
    public BaseResponse<Map<String, Object>> taskDependencies(
            @PathVariable long taskId,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) String defaultDb,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.getTaskDependencies(taskId, versionNo, defaultDb));
    }

    @PostMapping("/tasks/{taskId}/quality")
    public BaseResponse<Map<String, Object>> taskQuality(
            @PathVariable long taskId,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) String defaultDb,
            HttpServletRequest request) {
        String operator = currentUserService.requireObId(request);
        Map<String, Object> result = agentProxyService.checkTaskQuality(taskId, versionNo, defaultDb);
        taskVersionCheckService.record(taskId, versionNo, "QUALITY", result, operator);
        return BaseResponse.success(result);
    }

    @GetMapping("/hive/tables")
    public BaseResponse<Map<String, Object>> tables(
            @RequestParam(defaultValue = "") String pattern,
            @RequestParam(required = false) String db,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "分页参数非法");
        }
        return BaseResponse.success(agentProxyService.searchHiveTables(pattern, db, limit, offset));
    }

    @GetMapping("/hive/tables/{db}/{table}/columns")
    public BaseResponse<Map<String, Object>> columns(
            @PathVariable String db,
            @PathVariable String table,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.getHiveColumns(db, table));
    }

    @GetMapping("/hive/tables/{db}/{table}")
    public BaseResponse<Map<String, Object>> table(
            @PathVariable String db,
            @PathVariable String table,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.getHiveTable(db, table));
    }

    @GetMapping("/hive/tables/{db}/{table}/partitions")
    public BaseResponse<Map<String, Object>> partitions(
            @PathVariable String db,
            @PathVariable String table,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "分页参数非法");
        }
        return BaseResponse.success(agentProxyService.getHivePartitions(db, table, limit, offset));
    }

    @GetMapping("/hive/tables/{db}/{table}/ddl")
    public BaseResponse<Map<String, Object>> ddl(
            @PathVariable String db,
            @PathVariable String table,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.getHiveTableDdl(db, table));
    }

    @GetMapping("/hive/tables/{db}/{table}/statistics")
    public BaseResponse<Map<String, Object>> statistics(
            @PathVariable String db,
            @PathVariable String table,
            @RequestParam(required = false) List<String> columns,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        List<String> requestedColumns = columns == null ? Collections.emptyList() : columns;
        if (requestedColumns.size() > 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "统计字段不能超过 50 个");
        }
        return BaseResponse.success(agentProxyService.getHiveTableStatistics(db, table, requestedColumns));
    }

    @GetMapping("/hive/tables/{db}/{table}/storage-layout")
    public BaseResponse<Map<String, Object>> storageLayout(
            @PathVariable String db,
            @PathVariable String table,
            @RequestParam(defaultValue = "1000") int maxFiles,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (maxFiles < 1 || maxFiles > 5000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "文件采样上限非法");
        }
        return BaseResponse.success(agentProxyService.getHiveStorageLayout(db, table, maxFiles));
    }

    @GetMapping("/hive/tables/{db}/{table}/freshness")
    public BaseResponse<Map<String, Object>> freshness(
            @PathVariable String db,
            @PathVariable String table,
            @RequestParam(defaultValue = "2000") int partitionScanLimit,
            @RequestParam(defaultValue = "20") int pathSampleLimit,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (partitionScanLimit < 1 || partitionScanLimit > 5000
                || pathSampleLimit < 1 || pathSampleLimit > 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "新鲜度扫描上限非法");
        }
        return BaseResponse.success(agentProxyService.getHiveTableFreshness(
                db, table, partitionScanLimit, pathSampleLimit));
    }

    @PostMapping("/tasks/{taskId}/validate")
    public BaseResponse<Map<String, Object>> validate(
            @PathVariable long taskId,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) String defaultDb,
            HttpServletRequest request) {
        String operator = currentUserService.requireObId(request);
        Map<String, Object> result = agentProxyService.validateTaskSql(taskId, versionNo, defaultDb);
        taskVersionCheckService.record(taskId, versionNo, "VALIDATE", result, operator);
        return BaseResponse.success(result);
    }

    @PostMapping("/tasks/{taskId}/explain")
    public BaseResponse<Map<String, Object>> explain(
            @PathVariable long taskId,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) String defaultDb,
            @RequestParam(defaultValue = "false") boolean extended,
            HttpServletRequest request) {
        String operator = currentUserService.requireObId(request);
        Map<String, Object> result = agentProxyService.explainTaskSql(taskId, versionNo, defaultDb, extended);
        taskVersionCheckService.record(taskId, versionNo, "EXPLAIN", result, operator);
        return BaseResponse.success(result);
    }

    @PostMapping("/sql/completions")
    public BaseResponse<Map<String, Object>> completeSql(
            @Valid @RequestBody SqlCompletionRequestDTO body,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        if (body.getCursor() > body.getSql().length()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "cursor 超出 SQL 长度");
        }
        return BaseResponse.success(agentProxyService.completeSql(body));
    }

    @PostMapping("/sql/structure")
    public BaseResponse<Map<String, Object>> previewSqlStructure(
            @Valid @RequestBody SqlStructurePreviewDTO body,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(agentProxyService.previewSqlStructure(body));
    }

    @PostMapping("/sql/preview")
    public BaseResponse<Map<String, Object>> previewSql(
            @Valid @RequestBody SqlQueryPreviewDTO body,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(sqlQueryPreviewService.preview(body));
    }

    /** 复用版本发布的 Hive DDL 白名单，只做结构校验，不执行 DDL。 */
    @PostMapping("/sql/ddl/validate")
    public BaseResponse<Map<String, Object>> validateDdl(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        String ddl = body.get("ddl") == null ? "" : String.valueOf(body.get("ddl"));
        if (ddl.length() > 1_000_000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "DDL 内容过长");
        }
        final List<HiveDdlService.DdlStatement> statements;
        try {
            statements = hiveDdlService.parse(ddl);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), exception.getMessage());
        }
        if (statements.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "DDL 内容不能为空");
        }
        List<Map<String, Object>> details = statements.stream().map(statement -> Map.<String, Object>of(
                "type", statement.getType(), "table", statement.getTable(), "clause", statement.getClause()))
                .collect(java.util.stream.Collectors.toList());
        return BaseResponse.success(Map.of(
                "valid", true,
                "affectedTables", hiveDdlService.affectedTables(ddl),
                "statements", details,
                "executed", false));
    }
}
