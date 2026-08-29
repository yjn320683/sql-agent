package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.datacompare.model.CreateCompareRequest;
import com.yjn.sqlagent.datacompare.model.ForcePassRequest;
import com.yjn.sqlagent.datacompare.model.GenerateVersionPlanRequest;
import com.yjn.sqlagent.datacompare.model.PrepareVersionRequest;
import com.yjn.sqlagent.datacompare.model.UpdateCompareRuleRequest;
import com.yjn.sqlagent.exception.BusinessException;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.DataCompareFacadeService;
import java.util.Map;
import javax.validation.Valid;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/data-compares")
public class DataCompareController {
    private final DataCompareFacadeService facade;
    private final CurrentUserService currentUserService;

    public DataCompareController(DataCompareFacadeService facade, CurrentUserService currentUserService) {
        this.facade = facade;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/prepare-version")
    public BaseResponse<Map<String, Object>> prepareVersion(@Valid @RequestBody PrepareVersionRequest body,
                                                            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(facade.prepareVersion(body));
    }

    @PostMapping("/generate-version-sql")
    public BaseResponse<Map<String, Object>> generateVersionSql(
            @Valid @RequestBody GenerateVersionPlanRequest body, HttpServletRequest request) {
        return BaseResponse.success(facade.generateVersionPlan(body, currentUserService.requireObId(request)));
    }

    @PostMapping
    public BaseResponse<Map<String, Object>> create(@Valid @RequestBody CreateCompareRequest body,
                                                    HttpServletRequest request) {
        return BaseResponse.success(facade.create(body, currentUserService.requireObId(request)));
    }

    @GetMapping
    public BaseResponse<Map<String, Object>> list(@RequestParam(defaultValue = "all") String status,
                                                  @RequestParam(defaultValue = "all") String type,
                                                  @RequestParam(required = false) Long taskId,
                                                  @RequestParam(required = false) Integer versionNo,
                                                  @RequestParam(required = false) Long jobId,
                                                  @RequestParam(defaultValue = "") String operator,
                                                  @RequestParam(defaultValue = "") String fromTime,
                                                  @RequestParam(defaultValue = "") String toTime,
                                                  @RequestParam(defaultValue = "false") boolean mine,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int pageSize,
                                                  @RequestParam(defaultValue = "") String keyword,
                                                  HttpServletRequest request) {
        String currentUser = currentUserService.requireObId(request);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "分页参数非法");
        }
        if (keyword.length() > 256) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "keyword 不能超过256个字符");
        }
        return BaseResponse.success(facade.list(status, type, taskId, versionNo, jobId, operator,
                fromTime, toTime, mine, currentUser, page, pageSize, keyword));
    }

    @GetMapping("/{id}")
    public BaseResponse<Map<String, Object>> detail(@PathVariable long id, HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(facade.detail(id));
    }

    @GetMapping("/{id}/report")
    public BaseResponse<Map<String, Object>> report(@PathVariable long id,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    @RequestParam(defaultValue = "") String keyword,
                                                    HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(facade.report(id, page, pageSize, keyword));
    }

    @PutMapping("/tables/{id}/rule")
    public BaseResponse<Map<String, Object>> updateRule(@PathVariable long id,
                                                        @Valid @RequestBody UpdateCompareRuleRequest body,
                                                        HttpServletRequest request) {
        return BaseResponse.success(facade.updateRule(
                id, body.getRule(), currentUserService.requireObId(request)));
    }

    @PostMapping("/tables/{id}/rerun")
    public BaseResponse<Map<String, Object>> rerun(@PathVariable long id, HttpServletRequest request) {
        return BaseResponse.success(facade.rerun(id, currentUserService.requireObId(request)));
    }

    @PostMapping("/tables/{id}/force-pass")
    public BaseResponse<Map<String, Object>> forcePass(@PathVariable long id,
                                                       @Valid @RequestBody ForcePassRequest body,
                                                       HttpServletRequest request) {
        return BaseResponse.success(facade.forcePass(
                id, body.getReason(), currentUserService.requireObId(request)));
    }

    @PostMapping("/{id}/cancel")
    public BaseResponse<Map<String, Object>> cancel(@PathVariable long id, HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(facade.cancel(id));
    }

    @GetMapping("/tables/{id}/logs")
    public BaseResponse<Map<String, Object>> tableLog(@PathVariable long id, HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(facade.tableLog(id));
    }
}
