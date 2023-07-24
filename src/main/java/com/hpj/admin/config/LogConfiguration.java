package com.hpj.admin.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

@Configuration
public class LogConfiguration implements InitializingBean {

    @Autowired
    private Environment environment;
    private static final String APPLICATION_NAME = "spring.application.name";

    @Override
    public void afterPropertiesSet() throws Exception {
        fileAppender();
    }

    private void fileAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(getAppender(Level.INFO, context));
        logger.addAppender(getAppender(Level.WARN, context));
        logger.addAppender(getAppender(Level.ERROR, context));
    }

    private RollingFileAppender<ILoggingEvent> getAppender(Level level, LoggerContext context) {
        String applicationName = environment.getProperty(APPLICATION_NAME);
        // 滚动文件日志追加
        RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<>();
        fileAppender.setContext(context);
        // 格式化编码器
        PatternLayoutEncoder encoderBase = new PatternLayoutEncoder();
        encoderBase.setContext(context);
        // 设置格式化输出
        encoderBase.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        // 设置字符集为UTF-8
        encoderBase.setCharset(StandardCharsets.UTF_8);
        // 启动编码器
        encoderBase.start();
        fileAppender.setEncoder(encoderBase);
        // 滚动策略
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        // 设置文件名
        policy.setFileNamePattern("./" + level + "/" + applicationName + "-%d{yyyy-MM-dd}.%i.log");
        // 文件保留天数
        policy.setMaxHistory(7);
        // 单个文件大小
        policy.setMaxFileSize(FileSize.valueOf("100mb"));
        // 日志文件上限大小
        policy.setTotalSizeCap(FileSize.valueOf("2gb"));
        policy.setParent(fileAppender);
        // 启动滚动策略
        policy.start();
        fileAppender.setRollingPolicy(policy);
        // 筛选日志等级
        LevelFilter filter = new LevelFilter();
        filter.setLevel(level);
        fileAppender.addFilter(filter);
        // 启动文件日志追加
        fileAppender.start();
        return fileAppender;
    }
}
