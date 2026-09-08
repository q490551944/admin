package com.hpj.admin.common.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * @author huangpeijun
 * @date 2020/3/16
 */
@Configuration
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "false", matchIfMissing = true)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_INTERVAL_MILLIS = 10_000L;
    private static final int MESSAGE_SIZE_LIMIT_BYTES = 128 * 1024;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;
    private static final int SEND_TIME_LIMIT_MILLIS = 15_000;

    @Bean(name = "webSocketTaskScheduler")
    public ThreadPoolTaskScheduler webSocketTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("websocket-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/test")
                .withSockJS()
                .setHeartbeatTime(HEARTBEAT_INTERVAL_MILLIS)
                .setDisconnectDelay(30_000L);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{
                        HEARTBEAT_INTERVAL_MILLIS,
                        HEARTBEAT_INTERVAL_MILLIS
                })
                .setTaskScheduler(webSocketTaskScheduler());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(MESSAGE_SIZE_LIMIT_BYTES)
                .setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT_BYTES)
                .setSendTimeLimit(SEND_TIME_LIMIT_MILLIS);
    }
}
