package com.hpj.admin.common.config.monitor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The public page shell checks the independent monitoring session before showing identity. */
@Configuration(proxyBeanMethods = false)
public class MonitoringPageConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/monitor", "/monitor/index.html");
        registry.addRedirectViewController("/monitor/", "/monitor/index.html");
    }
}
