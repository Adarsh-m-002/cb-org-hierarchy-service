package com.hierarchyhub.service.impl;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.model.OrgDataRecord;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.producer.KafkaProducer;
import com.hierarchyhub.repository.BulkUploadCassandraRepository;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.service.OrgHierarchyService;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CloudStorageService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BulkLinkFileServiceImplTest {

    private final AuthUtils authUtils = mock(AuthUtils.class);
    private final CloudStorageService cloudStorageService = mock(CloudStorageService.class);
    private final BulkUploadCassandraRepository bulkUploadCassandraRepository = mock(BulkUploadCassandraRepository.class);
    private final KafkaProducer kafkaProducer = mock(KafkaProducer.class);
    private final OrgHierarchyRepository orgHierarchyRepository = mock(OrgHierarchyRepository.class);
    private final OrgHierarchyService orgHierarchyService = mock(OrgHierarchyService.class);
    private final OrgHierarchyLevelService orgHierarchyLevelService = mock(OrgHierarchyLevelService.class);
    private final BulkLinkFileServiceImpl service = new BulkLinkFileServiceImpl(authUtils, cloudStorageService,
            bulkUploadCassandraRepository, kafkaProducer, orgHierarchyRepository, orgHierarchyService, orgHierarchyLevelService);

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(service, "maxRows", 5);
        ReflectionTestUtils.setField(service, "bulkUploadTopic", "bulk-link-topic");
        when(authUtils.validateCallerAuthenticationAndFetchUserId(anyString())).thenReturn("user-1");
        when(authUtils.validateCallerAuthorizationAndFetchUserId(anyString())).thenReturn("user-1");
        when(cloudStorageService.newObjectKey(anyString())).thenAnswer(invocation -> "key_" + invocation.getArgument(0));
    }

    @Test
    public void buildSampleWorkbookDelegatesToMasterDataAndProducesAWorkbook() {
        when(orgHierarchyService.getMasterData("root-1")).thenReturn(List.of(new OrgDataRecord("C1", "Org One")));

        byte[] workbook = service.buildSampleWorkbook("root-1", "token");

        verify(authUtils).validateCallerAuthenticationAndFetchUserId("token");
        verify(orgHierarchyService).getMasterData("root-1");
        assertTrue(workbook.length > 0);
    }

    @Test
    public void buildExportWorkbookRejectsAnUnknownRoot() {
        when(orgHierarchyRepository.findSubtree("missing")).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.buildExportWorkbook("missing", "token"));
    }

    @Test
    public void buildExportWorkbookWalksLeavesAndUsesTheConfiguredLevelNames() {
        OrgHierarchyDto root = new OrgHierarchyDto("root-1", "Root Org", null, false, 1);
        OrgHierarchyDto leaf = new OrgHierarchyDto("C1", "Child One", "root-1", false, 0);
        when(orgHierarchyRepository.findSubtree("root-1")).thenReturn(List.of(root, leaf));
        doAnswer(invocation -> {
            OrgHierarchyRepository.PathRowCallback callback = invocation.getArgument(1);
            callback.process(root, 0, List.of("root-1"));
            callback.process(leaf, 1, List.of("root-1", "C1"));
            return null;
        }).when(orgHierarchyRepository).streamSubtreeWithPaths(eq("root-1"), any());
        when(orgHierarchyLevelService.levelMapFor("root-1")).thenReturn(
                Map.of(1, new HierarchyLevelRecord(1, "Ministry", "desc")));

        byte[] workbook = service.buildExportWorkbook("root-1", "token");

        verify(authUtils).validateCallerAuthenticationAndFetchUserId("token");
        assertTrue(workbook.length > 0);
    }

    @Test
    public void initiateRejectsAMissingFile() {
        assertThrows(ServiceException.class, () -> service.initiate(null, "root-1", "token"));
    }

    @Test
    public void initiateRejectsAnEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "sheet.xlsx", null, new byte[0]);

        assertThrows(ServiceException.class, () -> service.initiate(empty, "root-1", "token"));
    }

    @Test
    public void initiateRejectsABlankRootOrgId() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "sheet.xlsx", null, sheetWithRows(1));

        assertThrows(ServiceException.class, () -> service.initiate(file, " ", "token"));
    }

    @Test
    public void initiateRejectsAFileOverTheConfiguredRowLimit() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "sheet.xlsx", null, sheetWithRows(6)); // maxRows=5

        assertThrows(ServiceException.class, () -> service.initiate(file, "root-1", "token"));
        verify(cloudStorageService, never()).upload(any(), anyString());
    }

    @Test
    public void initiateUploadsRecordsAndPublishesTheJobOnSuccess() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "sheet.xlsx", null, sheetWithRows(2));

        ApiResponse response = service.initiate(file, "root-1", "token");

        verify(authUtils).validateCallerAuthorizationAndFetchUserId("token");
        verify(cloudStorageService).upload(any(byte[].class), eq("key_sheet.xlsx"));
        verify(bulkUploadCassandraRepository).insertInitiatedJob(eq("root-1"), anyString(), eq("sheet.xlsx"), eq("key_sheet.xlsx"), eq("user-1"));
        verify(kafkaProducer, times(1)).pushWithKey(eq("bulk-link-topic"), any(), eq("root-1"));
        assertEquals("sheet.xlsx", response.get("fileName"));
        assertEquals("user-1", response.get("createdBy"));
        assertEquals("root-1", response.get("rootOrgId"));
        assertEquals(Constants.INITIATED, response.get("status"));
        assertTrue(response.get("jobId") != null);
    }

    private static byte[] sheetWithRows(int rowCount) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("referenceSheet");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("parentOrganization");
            header.createCell(1).setCellValue("childOrganization");
            for (int r = 1; r <= rowCount; r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue("Parent<P1>");
                row.createCell(1).setCellValue("Child " + r + "<C" + r + ">");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
