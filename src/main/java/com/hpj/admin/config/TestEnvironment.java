package com.hpj.admin.config;

import com.hpj.admin.entity.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

@Configuration
public class TestEnvironment {

    @Bean
    public User user(StandardEnvironment environment){
        String s = environment.resolvePlaceholders("test.list");
        System.out.println(s);
        return new User();
    }
}
