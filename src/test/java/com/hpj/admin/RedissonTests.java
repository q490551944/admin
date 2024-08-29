package com.hpj.admin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.*;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RunWith(SpringRunner.class)
@SpringBootTest
public class RedissonTests {


    @Autowired
    private RedissonClient redissonClient;

    private static final Logger logger = LoggerFactory.getLogger(RedissonTests.class);

    @Test
    public void test() {
        RLongAdder aabb = redissonClient.getLongAdder("aabb");
        aabb.add(2);
        System.out.println(redissonClient);
    }

    @Test
    public void testStream() throws InterruptedException {
        RStream<String, String> a = redissonClient.getStream("a");
        new Thread(() -> {
            boolean flag = true;
            if (a.isExists()) {
                List<StreamGroup> listGroups = a.listGroups();
                for (StreamGroup streamGroup : listGroups) {
                    if (streamGroup.getName().equals("b")) {
                        flag = false;
                        break;
                    }
                }
            }
            if (flag) {
                redissonClient.getStream("a").createGroup("b", StreamMessageId.ALL);
            }
            while (true) {
                RFuture<Map<StreamMessageId, Map<String, String>>> mapRFuture = a.readGroupAsync("b", "a", StreamReadGroupArgs.greaterThan(StreamMessageId.NEVER_DELIVERED).count(1).timeout(Duration.ofSeconds(2)));
                try {
                    Map<StreamMessageId, Map<String, String>> map = mapRFuture.get();
                    map.forEach((streamMessageId, objectObjectMap) -> {
                        objectObjectMap.forEach((k, v) -> {
                            logger.info("streamMessageId:{},key:{}, value:{}", streamMessageId, k, v);
                        });
                        a.ack("b", streamMessageId);
                    });
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
        Thread.sleep(1000000);
    }

    @Test
    public void testSendStream() throws InterruptedException {
        RStream<String, String> a = redissonClient.getStream("a");
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                StreamAddArgs<String, String> entries = StreamAddArgs.entries("hpj", i + "", "cc", i + "");
                a.add(entries);
                try {
                    // 每500ms加一条数据
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
        Thread.sleep(100000);
    }
}
