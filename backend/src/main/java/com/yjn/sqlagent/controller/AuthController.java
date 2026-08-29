package com.yjn.sqlagent.controller;

import com.yjn.sqlagent.common.BaseResponse;
import com.yjn.sqlagent.model.dto.LoginRequestDTO;
import com.yjn.sqlagent.model.vo.LoginUserVO;
import com.yjn.sqlagent.service.CurrentUserService;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentUserService currentUserService;

    public AuthController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    public BaseResponse<LoginUserVO> login(@Valid @RequestBody LoginRequestDTO request,
                                           HttpServletRequest servletRequest) {
        String obId = currentUserService.loginLocal(servletRequest, request.getObId());
        return BaseResponse.success(new LoginUserVO(obId));
    }

    @GetMapping("/me")
    public BaseResponse<LoginUserVO> me(HttpServletRequest request) {
        return BaseResponse.success(new LoginUserVO(currentUserService.requireObId(request)));
    }

    @PostMapping("/logout")
    public BaseResponse<Void> logout(HttpServletRequest request) {
        currentUserService.logout(request);
        return BaseResponse.success(null);
    }
}
