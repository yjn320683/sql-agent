package com.yjn.sqlagent.service.impl;

import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.datacompare.model.CreateCompareRequest;
import com.yjn.sqlagent.datacompare.model.CompareRule;
import com.yjn.sqlagent.datacompare.model.GenerateVersionPlanRequest;
import com.yjn.sqlagent.datacompare.model.PrepareVersionRequest;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.service.DataCompareFacadeService;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class DataCompareFacadeServiceImpl implements DataCompareFacadeService {
    private final com.yjn.sqlagent.datacompare.service.DataCompareService dataCompareService;

    public DataCompareFacadeServiceImpl(
            com.yjn.sqlagent.datacompare.service.DataCompareService dataCompareService) {
        this.dataCompareService = dataCompareService;
    }

    @Override
    public Map<String, Object> prepareVersion(PrepareVersionRequest request) {
        return invoke(() -> dataCompareService.prepare(request));
    }

    @Override
    public Map<String, Object> generateVersionPlan(GenerateVersionPlanRequest request, String operatorObId) {
        return invoke(() -> dataCompareService.generate(request, operatorObId));
    }

    @Override
    public Map<String, Object> create(CreateCompareRequest request, String operatorObId) {
        return invoke(() -> dataCompareService.create(request, operatorObId));
    }

    @Override
    public Map<String, Object> list(String status, String type, Long taskId, Integer versionNo, Long jobId,
                                    String operator, String fromTime, String toTime, boolean mine,
                                    String currentUser, int page, int pageSize, String keyword) {
        return invoke(() -> dataCompareService.list(status, type, taskId, versionNo, jobId, operator,
                fromTime, toTime, mine, currentUser, page, pageSize, keyword));
    }

    @Override
    public Map<String, Object> detail(long id) {
        return invoke(() -> dataCompareService.detail(id));
    }

    @Override
    public Map<String, Object> report(long id, int page, int pageSize, String keyword) {
        return invoke(() -> dataCompareService.report(id, page, pageSize, keyword));
    }

    @Override
    public Map<String, Object> updateRule(long tableId, CompareRule rule, String operatorObId) {
        return invoke(() -> dataCompareService.updateRule(tableId, rule, operatorObId));
    }

    @Override
    public Map<String, Object> rerun(long tableId, String operatorObId) {
        return invoke(() -> dataCompareService.rerun(tableId, operatorObId));
    }

    @Override
    public Map<String, Object> forcePass(long tableId, String reason, String operatorObId) {
        return invoke(() -> dataCompareService.forcePass(tableId, reason, operatorObId));
    }

    @Override
    public Map<String, Object> cancel(long id) {
        return invoke(() -> Collections.<String, Object>singletonMap(
                "accepted", dataCompareService.cancel(id)));
    }

    @Override
    public Map<String, Object> tableLog(long tableId) {
        return invoke(() -> Collections.<String, Object>singletonMap(
                "content", dataCompareService.tableLog(tableId)));
    }

    private <T> T invoke(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), error.getMessage());
        }
    }
}
