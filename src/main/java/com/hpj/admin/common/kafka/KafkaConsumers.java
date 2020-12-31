package com.hpj.admin.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * @author huangpeijun
 * @date 2020/3/16
 */
public class KafkaConsumers implements Runnable {

    /**
     * 消费者
     */
    KafkaConsumer<String, String> kafkaConsumer;

    /**
     * topic
     */
    private List<String> topic;

    /**
     * kafka服务器地址
     */
    public String bootStrapServers;

    /**
     * 分组ID
     */
    public String groutId;

    /**
     * key序列化策略
     */
    public Class<?> keyDeserializer;

    /**
     * value序列化策略
     */
    public Class<?> valueDeserializer;


    public KafkaConsumers(List<String> topic, String bootStrapServers, String groutId, Class<?> keyDeserializer, Class<?> valueDeserializer) {
        this.topic = topic;
        this.bootStrapServers = bootStrapServers;
        this.groutId = groutId;
        this.keyDeserializer = keyDeserializer;
        this.valueDeserializer = valueDeserializer;
    }

    /**
     * 初始化消费者（配置写死是为了快速测试，请大家使用配置文件）
     *
     * @param topicList topic列表
     * @return 消费者实例
     */
    public KafkaConsumer<String, String> getInitConsumer(List<String> topicList) {
        //配置信息
        Properties props = new Properties();
        //kafka服务器地址
        props.put("bootstrap.servers", this.bootStrapServers);
        //必须指定消费者组
        props.put("group.id", this.groutId);
        //设置数据key和value的序列化处理类
        props.put("key.deserializer", this.keyDeserializer);
        props.put("value.deserializer", this.valueDeserializer);
        //创建消息者实例
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        //订阅topic的消息
        consumer.subscribe(topicList);
        return consumer;
    }

    @Override
    public void run() {
        kafkaConsumer = getInitConsumer(topic);
        boolean flag = true;
        while (flag) {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord<String, String> record : records) {
                if ("stop".equals(record.value())) {
                    flag = false;
                }
                System.out.println(record.value());
            }
        }
    }
}
