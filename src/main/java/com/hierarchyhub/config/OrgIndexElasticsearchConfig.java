package com.hierarchyhub.config;

import com.hierarchyhub.dto.Constants;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrgIndexElasticsearchConfig {

    private final ExternalServiceProperties externalServiceProperties;

    public OrgIndexElasticsearchConfig(ExternalServiceProperties externalServiceProperties) {
        this.externalServiceProperties = externalServiceProperties;
    }

    @Bean(name = Constants.ORG_INDEX_ELASTICSEARCH_CLIENT_BEAN)
    public RestClient orgIndexElasticsearchClient() {
        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                externalServiceProperties.getElasticsearchUsername(), externalServiceProperties.getElasticsearchPassword()));

        return RestClient.builder(new HttpHost(externalServiceProperties.getElasticsearchHost(),
                        externalServiceProperties.getElasticsearchPort()))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
                .build();
    }
}
