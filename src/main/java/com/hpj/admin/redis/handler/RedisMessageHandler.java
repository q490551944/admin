package com.hpj.admin.redis.handler;

import org.springframework.stereotype.Service;

@Service
public class RedisMessageHandler {

    public void getTestMessage(String message, String channel) {
        System.out.println("来自通道：" + channel);
        System.out.println("收到消息：" + message);
    }
}
