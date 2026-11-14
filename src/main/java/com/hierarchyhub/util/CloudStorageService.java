package com.hierarchyhub.util;

import com.hierarchyhub.config.ExternalServiceProperties;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.sunbird.cloud.storage.BaseStorageService;
import org.sunbird.cloud.storage.factory.StorageConfig;
import org.sunbird.cloud.storage.factory.StorageServiceFactory;
import scala.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class CloudStorageService {

    private static final Logger log = LoggerFactory.getLogger(CloudStorageService.class);

    private final ExternalServiceProperties externalServiceProperties;
    private BaseStorageService storageService;

    public CloudStorageService(ExternalServiceProperties externalServiceProperties) {
        this.externalServiceProperties = externalServiceProperties;
    }

    @PostConstruct
    public void init() {
        storageService = StorageServiceFactory.getStorageService(new StorageConfig(
                externalServiceProperties.getCloudStorageTypeName(), externalServiceProperties.getCloudStorageKey(),
                externalServiceProperties.getCloudStorageSecret().replace("\\n", "\n"),
                Option.apply(externalServiceProperties.getCloudStorageEndpoint()), Option.empty()));
    }

    /** Uploads {@code content} under the bare {@code objectKey} and returns the stored object's URL. */
    public String upload(byte[] content, String objectKey) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("bulk-link-upload-", ".xlsx");
            Files.write(tempFile, content);
            String url = storageService.upload(externalServiceProperties.getBulkUploadContainerName(), tempFile.toAbsolutePath().toString(),
                    fullCloudKey(objectKey), Option.apply(false), Option.apply(1), Option.apply(5), Option.empty());
            log.debug("bulkLink cloud storage upload succeeded: container={}, objectKey={}, url={}",
                    externalServiceProperties.getBulkUploadContainerName(), objectKey, url);
            return url;
        } catch (Exception e) { // the Scala SDK's own exceptions (auth, network, ...) are not all IOException
            log.error("bulkLink cloud storage upload failed: objectKey={}, error={}", objectKey, e.getMessage(), e);
            throw new ServiceException(ErrorCode.DEPENDENCY_FAILURE, "Could not upload file to cloud storage: " + e.getMessage());
        } finally {
            deleteQuietly(tempFile);
        }
    }

    /** Downloads the bare {@code objectKey} and returns its bytes. */
    public byte[] download(String objectKey) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("bulk-link-download-");
            log.debug("bulkLink cloud storage download starting: container={}, objectKey={}",
                    externalServiceProperties.getBulkUploadContainerName(), objectKey);

            storageService.download(externalServiceProperties.getBulkUploadContainerName(), fullCloudKey(objectKey),
                    tempDir.toAbsolutePath() + "/", Option.apply(false));
            Path downloaded = tempDir.resolve(objectKey);
            return Files.readAllBytes(downloaded);
        } catch (Exception e) { // the Scala SDK's own exceptions (auth, network, ...) are not all IOException
            log.error("bulkLink cloud storage download failed: objectKey={}, error={}", objectKey, e.getMessage(), e);
            throw new ServiceException(ErrorCode.DEPENDENCY_FAILURE, "Could not download file from cloud storage: " + e.getMessage());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    /** A fresh, bare (prefix-free) file name, unique enough it can never collide with any other upload. */
    public String newObjectKey(String fileName) {
        return UUID.randomUUID() + "_" + fileName;
    }

    private String fullCloudKey(String objectKey) {
        return externalServiceProperties.getBulkUploadObjectKeyPrefix() + "/" + objectKey;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp-file cleanup; never fail the request over it
        }
    }
}
