package com.hierarchyhub.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class ExternalServiceProperties {

    @Value("${org-service-host}")
    private String orgServiceHost;

    @Value("${org-update-endpoint}")
    private String orgUpdateEndpoint;

    @Value("${org-update-auth-token}")
    private String authToken;

    @Value("${org-update-auth-user-token}")
    private String authUserToken;

    @Value("${org-update-max-attempts}")
    private int maxAttempts;

    @Value("${org-update-retry-interval-ms}")
    private long retryIntervalMs;

    @Value("${cloud.storage.type.name}")
    private String cloudStorageTypeName;

    @Value("${cloud.storage.key}")
    private String cloudStorageKey;

    @Value("${cloud.storage.secret}")
    private String cloudStorageSecret;

    @Value("${cloud.storage.endpoint}")
    private String cloudStorageEndpoint;

    @Value("${org-hierarchy.bulk.upload.container-name}")
    private String bulkUploadContainerName;

    @Value("${org-hierarchy.bulk.upload.object-key-prefix}")
    private String bulkUploadObjectKeyPrefix;

    @Value("${org-hierarchy.elasticsearch.host}")
    private String elasticsearchHost;

    @Value("${org-hierarchy.elasticsearch.port}")
    private int elasticsearchPort;

    @Value("${org-hierarchy.elasticsearch.username}")
    private String elasticsearchUsername;

    @Value("${org-hierarchy.elasticsearch.password}")
    private String elasticsearchPassword;

    @Value("${org-hierarchy.elasticsearch.index-alias}")
    private String elasticsearchIndexAlias;

    @Value("${org-hierarchy.elasticsearch.scroll-page-size}")
    private int elasticsearchScrollPageSize;

    @Value("${org-hierarchy.elasticsearch.scroll-keep-alive-minutes}")
    private int elasticsearchScrollKeepAliveMinutes;
}
