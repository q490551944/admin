package com.hpj.admin.common.config.monitor;

import com.hpj.admin.monitor.MonitoringCatalog;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.RedisConnectionInspector;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfiguration {
    @Bean
    MonitoringCatalog monitoringCatalog(MonitoringProperties properties) {
        return new MonitoringCatalog(properties);
    }

    @Bean
    MonitoringConnectionResolver monitoringConnectionResolver(MonitoringProperties properties,
                                                              ConfigurableListableBeanFactory beanFactory) {
        return new MonitoringConnectionResolver(properties, beanFactory,
                List.of(new RedisConnectionInspector(), new StandardConnectionInspector()));
    }
}
