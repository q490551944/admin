package com.hpj.admin.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * @author huangpeijun
 * @date 2020/7/28
 */
@Configuration
@ConfigurationProperties(prefix = "spring.data.mongodb")
public class MongoDBDataSourceConfig extends AbstractMongoConfig {

    @Override
    public MongoTemplate getMongoTemplate(String database) throws Exception {
        return new MongoTemplate(mongoDbFactory(database));
    }
}
