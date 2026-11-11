package com.hierarchyhub.config;

import com.hierarchyhub.dto.Constants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ParentPathExecutorConfig {

    @Bean(Constants.PARENT_PATH_EXECUTOR)
    public ExecutorService parentPathExecutor(@Value("${org-hierarchy.parent-path.concurrency}") int concurrency) {
        return Executors.newFixedThreadPool(concurrency);
    }
}
