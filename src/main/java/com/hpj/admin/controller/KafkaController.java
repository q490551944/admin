package com.hpj.admin.controller;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * @author huangpeijun
 * @date 2020/3/16
 */
@RestController
@RequestMapping("/kafka")
public class KafkaController {

    @GetMapping
    public String createConsumer(@RequestParam String topic) {
        List<String> list = new ArrayList<>();
        list.add(topic);
        String bootStrapServers = "192.168.0.107";
        String groupId = "test";
        Properties props = new Properties();
        props.put("bootstrap.servers", bootStrapServers);
        // 消费者组编号
        props.put("group.id", groupId);
        // 反序列化key
        props.put("key.deserializer", StringDeserializer.class);
        // 反序列化value
        props.put("value.deserializer", StringDeserializer.class);
        KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(props);
        kafkaConsumer.subscribe(list);
//        KafkaConsumers kafkaConsumers = new KafkaConsumers(list, "192.168.0.107", "test", StringDeserializer.class, StringDeserializer.class);
//        kafkaConsumers.run();
        new Thread(() -> {
            while (true) {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(1000));
                for (ConsumerRecord<String, String> record : records) {
                    System.out.println(record.value());
                }
            }
        }).start();
        return null;
    }

}
