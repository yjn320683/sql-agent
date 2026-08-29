package com.yjn.sqlagent.config;

import com.yjn.sqlagent.realtime.common.RealtimeActorProvider;
import com.yjn.sqlagent.service.CurrentUserService;
import javax.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class RealtimeActorConfiguration {

    @Bean
    public RealtimeActorProvider realtimeActorProvider(CurrentUserService currentUserService) {
        return () -> {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) throw new IllegalStateException("当前请求上下文不存在");
            HttpServletRequest request = attributes.getRequest();
            return currentUserService.requireObId(request);
        };
    }
}
