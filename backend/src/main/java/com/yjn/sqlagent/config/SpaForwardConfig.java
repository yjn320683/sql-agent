package com.yjn.sqlagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Configuration
public class SpaForwardConfig {

    @Controller
    static class ForwardController {
        @GetMapping(value = {"/", "/{path:^(?!api|actuator|assets|.*\\..*$).*$}", "/{path:^(?!api|actuator|assets|.*\\..*$).*$}/**"})
        public String forward() {
            return "forward:/index.html";
        }
    }
}
