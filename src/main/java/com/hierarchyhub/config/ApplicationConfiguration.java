package com.hierarchyhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ApplicationConfiguration {

    @Value("${neo4j.authentication.username}")
    private String neo4jUserName;

    @Value("${neo4j.authentication.password}")
    private String neo4jPassword;

    @Value("${bolt.url}")
    private String boltUrl;

    @Value("${neo4j.maxlevel}")
    private int maxLevel;

    /**
     * common-util's own {@code OutboundRequestHandlerServiceImpl} needs this bean to exist. Backed by
     * {@link HttpComponentsClientHttpRequestFactory} (Apache HttpClient), not the default JDK
     * {@code HttpURLConnection}-based factory - the JDK one hard-refuses the PATCH method
     * ({@code ProtocolException: Invalid HTTP method: PATCH}), which the org-update sync call needs.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory());
    }

    public String getNeo4jUserName() {
        return neo4jUserName;
    }

    public void setNeo4jUserName(String neo4jUserName) {
        this.neo4jUserName = neo4jUserName;
    }

    public String getNeo4jPassword() {
        return neo4jPassword;
    }

    public void setNeo4jPassword(String neo4jPassword) {
        this.neo4jPassword = neo4jPassword;
    }

    public String getBoltUrl() {
        return boltUrl;
    }

    public void setBoltUrl(String boltUrl) {
        this.boltUrl = boltUrl;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = maxLevel;
    }
}
