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
}
