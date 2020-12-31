package com.hpj.admin.common.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.internal.MongoClientImpl;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDbFactory;

/**
 * @author huangpeijun
 * @date 2020/7/28
 */
public abstract class AbstractMongoConfig {

    private String uri;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public MongoDbFactory mongoDbFactory(String database) {
        MongoClient client = new MongoClientImpl(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri)).build(), null);
        return new SimpleMongoClientDbFactory(client, database);
    }

    abstract public MongoTemplate getMongoTemplate(String database) throws Exception;
}
