package com.hpj.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

/**
 * @author Huangpeijun
 */
@EnableCaching
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.hpj.admin.common.config")
@MapperScan("com.hpj.admin.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }

}
