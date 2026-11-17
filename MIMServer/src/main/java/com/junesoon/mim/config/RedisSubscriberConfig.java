package com.junesoon.mim.config;

import com.junesoon.mim.constant.CommonConstants;
import com.junesoon.mim.service.PushService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 订阅跨实例推送频道。每个实例都要订阅，才能覆盖「连接不在本机」的情况。
 */
@Configuration
public class RedisSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
        RedisConnectionFactory connectionFactory, PushService pushService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(pushService, new ChannelTopic(CommonConstants.REDIS_PUSH_CHANNEL));
        return container;
    }
}
