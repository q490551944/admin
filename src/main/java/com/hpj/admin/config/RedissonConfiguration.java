package com.hpj.admin.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfiguration {

    @Bean
    public RedissonClient redisson() {
        return Redisson.create();
    }
}
