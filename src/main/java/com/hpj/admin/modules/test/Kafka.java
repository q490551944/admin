package com.hpj.admin.modules.test;

import com.hpj.admin.common.kafka.KafkaConsumers;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.*;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * @author huangpeijun
 * @date 2020/3/23
 */
public class Kafka {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Map<String, Object> props = new HashMap<>(10);
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "192.168.0.107:9092");

        AdminClient adminClient = KafkaAdminClient.create(props);
        String topic = "hpj";
        System.out.println(adminClient.listTopics().names().get());
        List<String> list = new ArrayList<>();
//        NewTopic hpj = new NewTopic("hpj", 1, (short) 1);
//        Collection<NewTopic> newTopics = new ArrayList<>();
//        newTopics.add(hpj);
//        CreateTopicsResult topics = adminClient.createTopics(newTopics);
//
//        topics.all().get();
        DeleteTopicsResult deleteTopicsResult = adminClient.deleteTopics(list);
        deleteTopicsResult.all().get();
        adminClient.close();
    }
}
