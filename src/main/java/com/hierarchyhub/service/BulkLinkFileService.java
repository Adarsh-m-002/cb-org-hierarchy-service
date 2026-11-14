package com.hierarchyhub.service;

import com.hierarchyhub.model.ApiResponse;
import org.springframework.web.multipart.MultipartFile;

public interface BulkLinkFileService {

    ApiResponse initiate(MultipartFile file, String rootOrgId, String accessToken);

    /** An empty two-column template, prefilled with a {@code masterData} dropdown of {@code rootOrgId}'s candidate children. */
    byte[] buildSampleWorkbook(String rootOrgId, String accessToken);

    /** Every existing edge under {@code rootOrgId}, in the same shape, so it can be re-uploaded as-is. */
    byte[] buildExportWorkbook(String rootOrgId, String accessToken);
}
