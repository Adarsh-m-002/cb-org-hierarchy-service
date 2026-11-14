package com.hierarchyhub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hierarchyhub.dto.BatchLinkExecutionResult;
import com.hierarchyhub.dto.BulkLinkUploadEvent;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.OrgLinkErrorDetail;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.repository.BulkUploadCassandraRepository;
import com.hierarchyhub.service.OrgHierarchyService;
import com.hierarchyhub.util.CloudStorageService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BulkLinkUploadConsumerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CloudStorageService cloudStorageService = mock(CloudStorageService.class);
    private final BulkUploadCassandraRepository bulkUploadCassandraRepository = mock(BulkUploadCassandraRepository.class);
    private final OrgHierarchyService orgHierarchyService = mock(OrgHierarchyService.class);
    private final BulkLinkUploadConsumer consumer = new BulkLinkUploadConsumer(cloudStorageService, bulkUploadCassandraRepository, orgHierarchyService);

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(consumer, "chunkSize", 10);
        when(cloudStorageService.newObjectKey(anyString())).thenAnswer(invocation -> "key_" + invocation.getArgument(0));
    }

    @Test
    public void malformedEventIsLoggedAndIgnoredRatherThanThrown() {
        assertDoesNotThrow(() -> consumer.onBulkLinkUploadEvent("not valid json"));

        verify(bulkUploadCassandraRepository, never()).markInProgress(anyString(), anyString());
    }

    @Test
    public void successfulJobMarksInProgressThenTerminalSuccessfulWithUploadedResultFile() throws IOException {
        BulkLinkUploadEvent event = new BulkLinkUploadEvent("root-1", "job-1", "sheet.xlsx", "obj-1", "user-1");
        when(cloudStorageService.download("obj-1")).thenReturn(sheetWithRows("P1", "C1", "C2"));
        when(orgHierarchyService.linkChildrenUnderParentBatch(eq("P1"), eq(List.of("C1", "C2")), eq("user-1")))
                .thenReturn(new BatchLinkExecutionResult(List.of("C1", "C2"), List.of()));

        consumer.onBulkLinkUploadEvent(toJson(event));

        verify(bulkUploadCassandraRepository).markInProgress("root-1", "job-1");
        verify(cloudStorageService).upload(any(byte[].class), eq("key_" + Constants.RESULT_FILE_NAME_PREFIX + "sheet.xlsx"));
        verify(bulkUploadCassandraRepository).markTerminalWithResultFile(
                eq("root-1"), eq("job-1"), eq(Constants.SUCCESSFUL), eq(2), eq(2), eq(0), anyString());
    }

    @Test
    public void rowsAreGroupedByParentAndChunkedAccordingToTheConfiguredChunkSize() throws IOException {
        ReflectionTestUtils.setField(consumer, "chunkSize", 1); // forces 2 separate chunk calls for the 2 rows under P1
        BulkLinkUploadEvent event = new BulkLinkUploadEvent("root-1", "job-1", "sheet.xlsx", "obj-1", "user-1");
        when(cloudStorageService.download("obj-1")).thenReturn(sheetWithRows("P1", "C1", "C2"));
        when(orgHierarchyService.linkChildrenUnderParentBatch(eq("P1"), any(), eq("user-1")))
                .thenReturn(new BatchLinkExecutionResult(List.of("C1"), List.of()))
                .thenReturn(new BatchLinkExecutionResult(List.of("C2"), List.of()));

        consumer.onBulkLinkUploadEvent(toJson(event));

        verify(orgHierarchyService, times(2)).linkChildrenUnderParentBatch(eq("P1"), any(), eq("user-1"));
        verify(bulkUploadCassandraRepository).markTerminalWithResultFile(
                eq("root-1"), eq("job-1"), eq(Constants.SUCCESSFUL), eq(2), eq(2), eq(0), anyString());
    }

    @Test
    public void partialChunkFailureMarksTheWholeJobFailedWithTheRightCounts() throws IOException {
        BulkLinkUploadEvent event = new BulkLinkUploadEvent("root-1", "job-1", "sheet.xlsx", "obj-1", "user-1");
        when(cloudStorageService.download("obj-1")).thenReturn(sheetWithRows("P1", "C1", "C2"));
        when(orgHierarchyService.linkChildrenUnderParentBatch(eq("P1"), eq(List.of("C1", "C2")), eq("user-1")))
                .thenReturn(new BatchLinkExecutionResult(List.of("C1"), List.of(new OrgLinkErrorDetail("C2", "ERR", "already linked"))));

        consumer.onBulkLinkUploadEvent(toJson(event));

        verify(bulkUploadCassandraRepository).markTerminalWithResultFile(
                eq("root-1"), eq("job-1"), eq(Constants.STATUS_FAILED), eq(2), eq(1), eq(1), anyString());
    }

    @Test
    public void aChunkThrowingAServiceExceptionFailsJustThatChunksRowsNotTheWholeJob() throws IOException {
        BulkLinkUploadEvent event = new BulkLinkUploadEvent("root-1", "job-1", "sheet.xlsx", "obj-1", "user-1");
        when(cloudStorageService.download("obj-1")).thenReturn(sheetWithRows("P1", "C1", "C2"));
        when(orgHierarchyService.linkChildrenUnderParentBatch(eq("P1"), eq(List.of("C1", "C2")), eq("user-1")))
                .thenThrow(new ServiceException(ErrorCode.DEPENDENCY_FAILURE, "graph unavailable"));

        consumer.onBulkLinkUploadEvent(toJson(event));

        verify(bulkUploadCassandraRepository).markTerminalWithResultFile(
                eq("root-1"), eq("job-1"), eq(Constants.STATUS_FAILED), eq(2), eq(0), eq(2), anyString());
    }

    @Test
    public void unexpectedFailureDuringTheJobIsCaughtAndTheJobIsMarkedFailedBestEffort() {
        BulkLinkUploadEvent event = new BulkLinkUploadEvent("root-1", "job-1", "sheet.xlsx", "obj-1", "user-1");
        when(cloudStorageService.download("obj-1")).thenThrow(new RuntimeException("cloud storage down"));

        consumer.onBulkLinkUploadEvent(toJson(event));

        verify(bulkUploadCassandraRepository).markTerminal("root-1", "job-1", Constants.STATUS_FAILED, 0, 0, 0);
        verify(bulkUploadCassandraRepository, never()).markTerminalWithResultFile(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    public void evenTheBestEffortFailureMarkingItselfFailingNeverEscapesTheListener() {
        BulkLinkUploadEvent event = new BulkLinkUploadEvent("root-1", "job-1", "sheet.xlsx", "obj-1", "user-1");
        when(cloudStorageService.download("obj-1")).thenThrow(new RuntimeException("cloud storage down"));
        doThrow(new RuntimeException("cassandra also down"))
                .when(bulkUploadCassandraRepository).markTerminal(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());

        assertDoesNotThrow(() -> consumer.onBulkLinkUploadEvent(toJson(event)));
    }

    private static String toJson(BulkLinkUploadEvent event) {
        try {
            return OBJECT_MAPPER.writeValueAsString(event);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] sheetWithRows(String parentId, String... childIds) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("referenceSheet");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("parentOrganization");
            header.createCell(1).setCellValue("childOrganization");
            int r = 1;
            for (String childId : childIds) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue("Parent<" + parentId + ">");
                row.createCell(1).setCellValue("Child<" + childId + ">");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
