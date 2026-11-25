package com.hierarchyhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * An explicit, named {@link PlatformTransactionManager} for the PostgreSQL {@link DataSource} that
 * {@code @Transactional(ORG_HIERARCHY_TX_MANAGER)} on {@link com.hierarchyhub.service.impl.OrgHierarchyServiceImpl}
 * and friends binds to by name - kept as a deliberate, named bean rather than relying on Spring Boot's own
 * single-DataSource auto-configured default, so the transaction manager this module actually uses stays explicit.
 */
@Configuration
public class OrgHierarchyTransactionConfig {

    public static final String ORG_HIERARCHY_TX_MANAGER = "orgHierarchyTransactionManager";

    @Bean(ORG_HIERARCHY_TX_MANAGER)
    public PlatformTransactionManager orgHierarchyTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
