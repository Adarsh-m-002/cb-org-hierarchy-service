package com.hierarchyhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class BulkLinkExecutorConfig {

    public static final String BULK_LINK_EXECUTOR = "bulkLinkExecutor";

    @Bean(BULK_LINK_EXECUTOR)
    public Executor bulkLinkExecutor(@Value("${org-hierarchy.bulk.core-pool-size}") int corePoolSize,
                                      @Value("${org-hierarchy.bulk.max-pool-size}") int maxPoolSize,
                                      @Value("${org-hierarchy.bulk.queue-capacity}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("bulk-link-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
