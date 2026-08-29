package com.yjn.sqlagent.service;

import com.yjn.sqlagent.datacompare.model.CreateCompareRequest;
import com.yjn.sqlagent.datacompare.model.CompareRule;
import com.yjn.sqlagent.datacompare.model.GenerateVersionPlanRequest;
import com.yjn.sqlagent.datacompare.model.PrepareVersionRequest;
import java.util.Map;

/**
 * Backend 对验数模块的应用层入口。
 */
public interface DataCompareFacadeService {
    Map<String, Object> prepareVersion(PrepareVersionRequest request);
    Map<String, Object> generateVersionPlan(GenerateVersionPlanRequest request, String operatorObId);
    Map<String, Object> create(CreateCompareRequest request, String operatorObId);
    Map<String, Object> list(String status, String type, Long taskId, Integer versionNo, Long jobId,
                             String operator, String fromTime, String toTime, boolean mine,
                             String currentUser, int page, int pageSize, String keyword);
    Map<String, Object> detail(long id);
    Map<String, Object> report(long id, int page, int pageSize, String keyword);
    Map<String, Object> updateRule(long tableId, CompareRule rule, String operatorObId);
    Map<String, Object> rerun(long tableId, String operatorObId);
    Map<String, Object> forcePass(long tableId, String reason, String operatorObId);
    Map<String, Object> cancel(long id);
    Map<String, Object> tableLog(long tableId);
}
