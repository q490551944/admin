package com.hpj.admin;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.CollectionOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(SpringRunner.class)
@SpringBootTest
public class AdminApplicationTests {

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private KafkaProperties kafkaProperties;
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final Logger logger = LoggerFactory.getLogger(AdminApplicationTests.class);

    @Test
    public void test() {
        Set<Integer> set = new HashSet<>();
        mongoTemplate.createCollection("a", CollectionOptions.emitChangedRevisions());
    }

    @Test
    public void testThymeleaf() throws IOException, TemplateException {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_28);
        ClassPathResource classPathResource = new ClassPathResource("templates");
        cfg.setDirectoryForTemplateLoading(classPathResource.getFile());
        Map<String, Object> map = new HashMap<>();
        map.put("x", 1);
        StringWriter writer = new StringWriter();
        cfg.getTemplate("test.txt").process(map, writer);
        writer.flush();
        String string = writer.toString();
        System.out.println(string);
    }

    @Test
    public void testSend() {
//        Properties producerProperties = new Properties();
//        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
//        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
//        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
//        producerProperties.put(ProducerConfig.SECURITY_PROVIDERS_CONFIG)
//        KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>();
        kafkaTemplate.send("aabb", "ccdd".getBytes());
    }

}
