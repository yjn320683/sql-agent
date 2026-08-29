package com.yjn.sqlagent.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient agentWebClient(AgentProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofMinutes(10));
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-Agent-Service-Token", properties.getServiceToken())
                .defaultHeader(HttpHeaders.USER_AGENT, "sql-agent-backend")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
