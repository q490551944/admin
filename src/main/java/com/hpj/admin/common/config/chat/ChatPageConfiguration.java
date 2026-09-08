package com.hpj.admin.common.config.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring's static resource handler does not resolve nested directory index pages. */
@Configuration(proxyBeanMethods = false)
public class ChatPageConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/chat", "/chat/index.html").setKeepQueryParams(true);
        registry.addRedirectViewController("/chat/", "/chat/index.html").setKeepQueryParams(true);
    }
}
