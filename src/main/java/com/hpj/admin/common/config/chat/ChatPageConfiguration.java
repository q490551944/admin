package com.hpj.admin.common.config.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring 静态资源处理器不会自动补全子目录首页，因此显式重定向聊天入口。 */
@Configuration(proxyBeanMethods = false)
public class ChatPageConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 保留 mode 等查询参数，使目录入口与直接访问 index.html 使用相同运行模式。
        registry.addRedirectViewController("/chat", "/chat/index.html").setKeepQueryParams(true);
        registry.addRedirectViewController("/chat/", "/chat/index.html").setKeepQueryParams(true);
    }
}
