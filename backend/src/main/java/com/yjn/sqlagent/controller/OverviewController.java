package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.OverviewService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overview")
public class OverviewController {
    private final OverviewService overviewService;
    private final CurrentUserService currentUserService;

    public OverviewController(OverviewService overviewService, CurrentUserService currentUserService) {
        this.overviewService = overviewService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public BaseResponse<Map<String, Object>> overview(HttpServletRequest request) {
        return BaseResponse.success(overviewService.overview(currentUserService.requireObId(request)));
    }
}
