package com.yixin.interview.config;

import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * redissonClient 依赖低版本不匹配，注入不成功，手动注入
 * 自动注入支持 redisson 版本为  3.19.0  -  x.x.x(3.27.0)
 *
 * @author 亦-Nickname
 */
@Configuration
@ConfigurationProperties(prefix = "spring.redis")
@Data
public class RedissonConfig {

    private String host;

    private Integer port;

    private Integer database;

    private String password;

    private Integer timeout;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        //指定编码，默认编码为org.redisson.codec.JsonJacksonCodec
        //config.setCodec(new org.redisson.client.codec.StringCodec());
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setPassword(password)
                .setConnectionPoolSize(50)
                .setIdleConnectionTimeout(timeout);

        return Redisson.create(config);
    }
}