package com.hpj.admin.common.config;

import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author huangpeijun
 * @date 2020/3/4
 */
@Configuration
@ConditionalOnClass(value = {PaginationInnerInterceptor.class})
public class MybatisPlusConfig {

    @Bean
    public PaginationInnerInterceptor paginationInterceptor() {
        return new PaginationInnerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "auditMetaObjectHandler")
    public AuditMetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }

}