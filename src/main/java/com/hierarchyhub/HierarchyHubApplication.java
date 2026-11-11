package com.hierarchyhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

/**
 * Spring Boot 3's own generic auto-configured repository scanning (triggered merely by having the right classes on
 * the classpath - it doesn't matter whether any application interface actually uses them) needs a newer
 * spring-data-commons than the one this app is pinned to for the legacy, OGM-based v1 Neo4j module (see
 * {@code pom.xml}) - mixing the two throws {@code NoSuchMethodError} on
 * {@code AnnotationRepositoryConfigurationSource}'s constructor at startup (confirmed against a live run, not
 * guessed). Excluding all four here has no functional effect: Redis/JDBC are never used via a Spring Data repository
 * interface (only {@code RedisTemplate}/{@code NamedParameterJdbcTemplate} directly), and the legacy
 * {@link com.hierarchyhub.repository.OrganizationRepository} stopped being a Spring Data repository interface
 * entirely (see its own javadoc) - but {@code Neo4jRepositoriesAutoConfiguration} still matches on classpath
 * presence alone ({@code org.neo4j.driver.Driver} + {@code org.springframework.data.neo4j.repository.Neo4jRepository}
 * are both still on the classpath via the pinned old spring-data-neo4j/neo4j-ogm-bolt-driver jars), so it must stay
 * excluded even though nothing in this app implements that interface any more.
 */
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
        JdbcRepositoriesAutoConfiguration.class, Neo4jRepositoriesAutoConfiguration.class, Neo4jDataAutoConfiguration.class})
public class HierarchyHubApplication {

	public static void main(String[] args) {
		SpringApplication.run(HierarchyHubApplication.class, args);
	}

}
