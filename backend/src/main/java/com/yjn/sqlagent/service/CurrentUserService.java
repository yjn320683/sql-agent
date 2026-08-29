package com.yjn.sqlagent.service;

import com.yjn.sqlagent.common.ErrorCode;
import com.yjn.sqlagent.config.AuthProperties;
import com.yjn.sqlagent.exception.BusinessException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    private static final String SESSION_OB_ID = "SQL_AGENT_OB_ID";

    private final AuthProperties properties;

    public CurrentUserService(AuthProperties properties) {
        this.properties = properties;
    }

    public String loginLocal(HttpServletRequest request, String obId) {
        if (!properties.isLocalLoginEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "当前环境未启用本地登录");
        }
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SESSION_OB_ID, obId);
        return obId;
    }

    public String requireObId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object obId = session == null ? null : session.getAttribute(SESSION_OB_ID);
        if (!(obId instanceof String) || ((String) obId).isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        }
        return (String) obId;
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
