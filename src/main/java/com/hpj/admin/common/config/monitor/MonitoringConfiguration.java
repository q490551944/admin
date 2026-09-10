package com.hpj.admin.common.config.monitor;

import com.hpj.admin.monitor.MonitoringCatalog;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.RedisConnectionInspector;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
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

    @Bean
    MonitoringAdapterRegistry monitoringAdapterRegistry(ObjectProvider<MonitoringAdapter> adapters) {
        return new MonitoringAdapterRegistry(adapters.orderedStream().toList());
    }

    @Bean
    MonitoringSnapshotStore monitoringSnapshotStore(MonitoringProperties properties) {
        return new MonitoringSnapshotStore(properties.getMaxTargets(), 5000, properties.getMaxTargets());
    }

    @Bean
    MonitoringCounterStore monitoringCounterStore(MonitoringProperties properties) {
        return new MonitoringCounterStore(properties.getMaxTargets(), 5000);
    }

    @Bean(destroyMethod = "close")
    MonitoringScheduler monitoringScheduler(MonitoringProperties properties, MonitoringConnectionResolver resolver,
                                             MonitoringAdapterRegistry adapters, MonitoringSnapshotStore snapshots,
                                             MonitoringCounterStore counters) {
        return new MonitoringScheduler(properties, resolver::resolve, adapters, snapshots, counters);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startMonitoringAfterReadiness(MonitoringScheduler scheduler) {
        return event -> scheduler.start();
    }
}
