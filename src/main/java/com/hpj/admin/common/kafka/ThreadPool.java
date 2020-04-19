package com.hpj.admin.common.kafka;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;

/**
 * @author huangpeijun
 * @date 2020/3/15
 */
public class ThreadPool implements InitializingBean {

    /**
     * 消费者
     */
    private KafkaConsumer<String, String> consumer;

    @Override
    public void afterPropertiesSet() throws Exception {

    }
}
