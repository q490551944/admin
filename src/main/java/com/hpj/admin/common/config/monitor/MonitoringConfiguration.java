package com.hpj.admin.common.config.monitor;

import com.hpj.admin.monitor.MonitoringCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfiguration {
    @Bean
    MonitoringCatalog monitoringCatalog(MonitoringProperties properties) {
        return new MonitoringCatalog(properties);
    }
}
