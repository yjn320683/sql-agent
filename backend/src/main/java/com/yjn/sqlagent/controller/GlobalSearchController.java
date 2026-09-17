package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.service.CurrentUserService;
import com.yjn.sqlagent.service.GlobalSearchService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class GlobalSearchController {
    private final GlobalSearchService searchService;
    private final CurrentUserService currentUserService;

    public GlobalSearchController(GlobalSearchService searchService, CurrentUserService currentUserService) {
        this.searchService = searchService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public BaseResponse<Map<String, Object>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int pageSize,
            HttpServletRequest request) {
        String actor = currentUserService.requireObId(request);
        return BaseResponse.success(searchService.search(keyword, type, page, pageSize, actor));
    }
}
