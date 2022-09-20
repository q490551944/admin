package com.hpj.admin.redis.consumer;

import com.hpj.admin.redis.handler.RedisMessageHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class CustomRedisListener {

    @Autowired
    private RedisMessageHandler redisMessageHandler;

    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory factory) {
        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(factory);

        listenerContainer.addMessageListener(new MessageListenerAdapter(redisMessageHandler, "getTestMessage"), new PatternTopic("test_topic"));
        return listenerContainer;
    }
}
