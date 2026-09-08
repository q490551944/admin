package com.hpj.admin.chat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.ChatRoomService;
import com.hpj.admin.common.config.chat.ChatProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import java.time.Duration;

/** 配置聊天 STOMP 端点、内存消息代理，以及连接收发两侧的 Session 校验。 */
@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final ChatProperties properties;
    private final ChatAccounts accounts;
    private final ChatRoomService rooms;
    private final ObjectMapper json;
    private final ChatSocketSessions sockets;

    public ChatWebSocketConfiguration(ChatProperties properties, ChatAccounts accounts, ChatRoomService rooms,
                                      ObjectMapper json) {
        this.properties = properties;
        this.accounts = accounts;
        this.rooms = rooms;
        this.json = json;
        this.sockets = new ChatSocketSessions(accounts);
    }

    @Bean
    public ThreadPoolTaskScheduler chatWebSocketScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("chat-websocket-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    public SmartInitializingSingleton chatSessionExpiryMonitor(
            @Qualifier("chatWebSocketScheduler") ThreadPoolTaskScheduler scheduler) {
        // 等待 Spring 完成调度器初始化后再启动巡检，避免手动初始化导致重复执行器泄漏。
        return () -> scheduler.scheduleWithFixedDelay(sockets::closeExpired, Duration.ofSeconds(5));
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 空列表沿用同源限制；显式配置只接受精确 HTTP(S) Origin，不开放通配来源。
        String[] origins = properties.getSecurity().getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank()).toArray(String[]::new);
        for (String origin : origins) {
            if (origin.contains("*") || !(origin.startsWith("http://") || origin.startsWith("https://"))) {
                throw new IllegalArgumentException("chat.security.allowed-origins requires exact HTTP(S) origins");
            }
        }
        registry.setErrorHandler(new ChatStompErrorHandler(json));
        registry.addEndpoint("/ws/chat").setAllowedOrigins(origins)
                .addInterceptors(new ChatSessionHandshake(accounts));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /app 交给应用处理，/topic 与 /queue 由当前进程的简单消息代理投递。
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000}).setTaskScheduler(chatWebSocketScheduler());
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 先从 Session 确认并写入用户，再由安全上下文拦截器向消息处理线程传播身份。
        registration.interceptors(new ChatInboundInterceptor(accounts, rooms, json),
                new SecurityContextChannelInterceptor());
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                // 只拦截业务消息；即使认证已失效，ERROR/DISCONNECT 仍需送达客户端。
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE
                        && !sockets.valid(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()))) return null;
                return message;
            }
        });
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        // 限制单条消息、待发送缓冲及发送耗时，避免慢连接无限占用资源。
        registry.setMessageSizeLimit(128 * 1024).setSendBufferSizeLimit(512 * 1024).setSendTimeLimit(15000);
        registry.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.add(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sockets.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }
}
