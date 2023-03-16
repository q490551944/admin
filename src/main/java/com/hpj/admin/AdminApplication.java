package com.hpj.admin;

import com.hpj.admin.config.TestProperties;
import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

/**
 * @author Huangpeijun
 */
@EnableAdminServer
@EnableCaching
@EnableConfigurationProperties(value = TestProperties.class)
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
@MapperScan("com.hpj.admin.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }

}
