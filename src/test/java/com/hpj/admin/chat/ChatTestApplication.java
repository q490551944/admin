package com.hpj.admin.chat;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.hpj.admin.chat.security.*;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.chat.ChatPageConfiguration;
import com.hpj.admin.common.exception.MyExceptionHandler;
import com.hpj.admin.controller.chat.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableConfigurationProperties(ChatProperties.class)
@MapperScan("com.hpj.admin.mapper.chat")
@Import({ChatSecurityConfiguration.class, LegacySecurityConfiguration.class, ChatWebSocketConfiguration.class,
        ChatAccounts.class, ChatRoomService.class, RoomEventPublisher.class, ChatSessionController.class,
        ChatRoomController.class, ChatExceptionAdvice.class, ChatPageConfiguration.class, MyExceptionHandler.class,
        ChatMessagingService.class, ChatMessagePublisher.class, ChatMessageController.class, ChatSubscriptionReceipts.class,
        ChatDirectService.class, ChatDirectController.class, ChatAccessAudit.class, ChatAttachmentAccessConfiguration.class,
        ChatAttachmentService.class, ChatAttachmentController.class, ChatImageValidator.class, MinioChatObjectStorage.class, ChatMetrics.class})
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class, FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class, JacksonAutoConfiguration.class,
        ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class, WebSocketServletAutoConfiguration.class,
        SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration.class})
public class ChatTestApplication {}
