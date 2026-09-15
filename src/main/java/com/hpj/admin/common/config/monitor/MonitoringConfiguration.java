package com.hpj.admin.common.config.monitor;

import com.hpj.admin.monitor.MonitoringCatalog;
import com.hpj.admin.monitor.api.MonitoringReadService;
import com.hpj.admin.monitor.api.MonitoringResponseProjector;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.RedisConnectionInspector;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler;
import com.hpj.admin.monitor.mysql.MysqlMonitoringAdapter;
import com.hpj.admin.monitor.mysql.MysqlMonitoringConnections;
import com.hpj.admin.monitor.redis.RedisMonitoringAdapter;
import com.hpj.admin.monitor.redis.RedisMonitoringConnections;
import com.hpj.admin.monitor.kafka.KafkaMonitoringAdapter;
import com.hpj.admin.monitor.kafka.KafkaMonitoringConnections;
import com.hpj.admin.monitor.mongodb.MongoMonitoringAdapter;
import com.hpj.admin.monitor.mongodb.MongoMonitoringConnections;
import com.hpj.admin.monitor.elasticsearch.ElasticsearchMonitoringAdapter;
import com.hpj.admin.monitor.elasticsearch.ElasticsearchMonitoringConnections;
import com.hpj.admin.monitor.minio.MinioMonitoringAdapter;
import com.hpj.admin.monitor.minio.MinioMonitoringConnections;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfiguration {
    @Bean
    MinioMonitoringAdapter minioMonitoringAdapter(MonitoringProperties properties) {
        return new MinioMonitoringAdapter(properties, new MinioMonitoringConnections(), Clock.systemUTC());
    }

    @Bean
    ElasticsearchMonitoringAdapter elasticsearchMonitoringAdapter(MonitoringProperties properties) {
        return new ElasticsearchMonitoringAdapter(properties, new ElasticsearchMonitoringConnections(), Clock.systemUTC());
    }

    @Bean
    MongoMonitoringAdapter mongoMonitoringAdapter(MonitoringProperties properties) {
        return new MongoMonitoringAdapter(properties, new MongoMonitoringConnections(), Clock.systemUTC());
    }

    @Bean
    KafkaMonitoringAdapter kafkaMonitoringAdapter(MonitoringProperties properties) {
        return new KafkaMonitoringAdapter(properties, new KafkaMonitoringConnections(), Clock.systemUTC());
    }

    @Bean
    RedisMonitoringAdapter redisMonitoringAdapter(MonitoringProperties properties) {
        return new RedisMonitoringAdapter(properties, new RedisMonitoringConnections(), Clock.systemUTC());
    }

    @Bean
    MysqlMonitoringAdapter mysqlMonitoringAdapter(MonitoringProperties properties) {
        return new MysqlMonitoringAdapter(properties, new MysqlMonitoringConnections(), Clock.systemUTC());
    }

    @Bean
    MonitoringResponseProjector monitoringResponseProjector() {
        return new MonitoringResponseProjector();
    }

    @Bean
    MonitoringReadService monitoringReadService(MonitoringScheduler scheduler, MonitoringResponseProjector projector) {
        return new MonitoringReadService(scheduler, projector, Clock.systemUTC());
    }

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
