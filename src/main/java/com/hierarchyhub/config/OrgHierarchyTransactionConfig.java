package com.hierarchyhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * The org-hierarchy v2 module (link / move / unlink) only ever talks to PostgreSQL, but the app also carries a
 * {@code Neo4jTransactionManager} bean from the older v1 Neo4j code. Without this bean, that Neo4j manager would be
 * the only {@link PlatformTransactionManager} in the context and {@code @Transactional} on
 * {@link com.hierarchyhub.service.impl.OrgHierarchyServiceImpl} would silently bind to it instead of a
 * real JDBC transaction - meaning the row locking and rollback this module depends on would not actually happen.
 */
@Configuration
public class OrgHierarchyTransactionConfig {

    public static final String ORG_HIERARCHY_TX_MANAGER = "orgHierarchyTransactionManager";

    @Bean(ORG_HIERARCHY_TX_MANAGER)
    public PlatformTransactionManager orgHierarchyTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
