package com.example.lottery.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池配置
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {
    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    /**
     * 抽签预处理线程池
     */
    @Bean
    public ThreadPoolExecutor lotteryThreadPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,  // 核心线程数
                5,  // 最大线程数
                60L, // 空闲线程存活时间
                TimeUnit.SECONDS, // 时间单位
                new LinkedBlockingQueue<>(100), // 任务队列
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("lottery-preprocess-" + thread.getId());
                    thread.setDaemon(false);
                    return thread;
                },
                (r, executor1) -> {
                    log.warn("抽签预处理线程池任务队列已满，拒绝执行任务");
                    throw new RuntimeException("抽签预处理线程池任务队列已满");
                }
        );
        
        log.info("抽签预处理线程池初始化完成 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}", 
                executor.getCorePoolSize(), executor.getMaximumPoolSize(), 
                ((LinkedBlockingQueue<?>) executor.getQueue()).remainingCapacity());
        
        return executor;
    }
} 