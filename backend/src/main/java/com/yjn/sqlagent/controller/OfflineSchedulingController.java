package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.model.dto.SqlTaskBackfillCreateDTO;
import com.yjn.sqlagent.model.dto.SqlTaskDependencySaveDTO;
import com.yjn.sqlagent.model.dto.SqlTaskScheduleSaveDTO;
import com.yjn.sqlagent.model.entity.SqlTaskBackfillBatch;
import com.yjn.sqlagent.model.entity.SqlTaskDependency;
import com.yjn.sqlagent.model.vo.SqlTaskScheduleVO;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.OfflineSchedulingService;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OfflineSchedulingController {
    private final OfflineSchedulingService schedulingService;
    private final CurrentUserService currentUserService;

    public OfflineSchedulingController(OfflineSchedulingService schedulingService,
                                       CurrentUserService currentUserService) {
        this.schedulingService = schedulingService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/tasks/{taskId}/schedule")
    public BaseResponse<SqlTaskScheduleVO> getSchedule(@PathVariable long taskId,
                                                       HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(schedulingService.getSchedule(taskId));
    }

    @PutMapping("/tasks/{taskId}/schedule")
    public BaseResponse<SqlTaskScheduleVO> saveSchedule(@PathVariable long taskId,
                                                        @Valid @RequestBody SqlTaskScheduleSaveDTO body,
                                                        HttpServletRequest request) {
        return BaseResponse.success(schedulingService.saveSchedule(
                currentUserService.requireObId(request), taskId, body));
    }

    @GetMapping("/tasks/{taskId}/dependencies")
    public BaseResponse<List<SqlTaskDependency>> getDependencies(@PathVariable long taskId,
                                                                 HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(schedulingService.getDependencies(taskId));
    }

    @PutMapping("/tasks/{taskId}/dependencies")
    public BaseResponse<List<SqlTaskDependency>> saveDependencies(
            @PathVariable long taskId,
            @Valid @RequestBody SqlTaskDependencySaveDTO body,
            HttpServletRequest request) {
        return BaseResponse.success(schedulingService.saveDependencies(
                currentUserService.requireObId(request), taskId, body));
    }

    @GetMapping("/schedules/dag")
    public BaseResponse<Map<String, Object>> dag(HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(schedulingService.dag());
    }

    @PostMapping("/tasks/{taskId}/backfills")
    public BaseResponse<SqlTaskBackfillBatch> createBackfill(
            @PathVariable long taskId,
            @Valid @RequestBody SqlTaskBackfillCreateDTO body,
            HttpServletRequest request) {
        return BaseResponse.success(schedulingService.createBackfill(
                currentUserService.requireObId(request), taskId, body));
    }

    @GetMapping("/tasks/{taskId}/backfills")
    public BaseResponse<Map<String, Object>> listBackfills(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(schedulingService.listBackfills(taskId, page, pageSize));
    }

    @GetMapping("/tasks/{taskId}/schedule-runs")
    public BaseResponse<Map<String, Object>> listRuns(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        currentUserService.requireObId(request);
        return BaseResponse.success(schedulingService.listRuns(taskId, page, pageSize));
    }
}
