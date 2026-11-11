package com.hierarchyhub.config;

import org.neo4j.ogm.config.Configuration;
import org.neo4j.ogm.session.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.neo4j.transaction.Neo4jTransactionManager;

@org.springframework.context.annotation.Configuration
public class Neo4jConfig {

    @Autowired ApplicationConfiguration configuration;

    @Bean
    public Configuration configuration() {
        return new Configuration.Builder()
                .uri(configuration.getBoltUrl())
                .credentials(configuration.getNeo4jUserName(), configuration.getNeo4jPassword())
                .build();
    }

    @Bean
    public SessionFactory sessionFactory() {
        return new SessionFactory(configuration(), "com.hierarchyhub.model");
    }

    @Bean
    public Neo4jTransactionManager transactionManager(SessionFactory sessionFactory) {
        return new Neo4jTransactionManager(sessionFactory);
    }

}
