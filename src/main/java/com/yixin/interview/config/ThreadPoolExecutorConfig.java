package com.yixin.interview.config;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class ThreadPoolExecutorConfig {

    // 线程工厂
    private static final ThreadFactory threadFactory = new BasicThreadFactory.Builder()
            .namingPattern("openai-pool-%d")
            .daemon(true)
            .build();

    // 等待队列
    private static final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(100);
    @Bean
    public ThreadPoolExecutor threadPoolExecutor() {
        return new ThreadPoolExecutor(
                5,
                10,
                100,
                TimeUnit.SECONDS,
                workQueue,
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
                );
    }
}
