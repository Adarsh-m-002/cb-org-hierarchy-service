package com.hierarchyhub.util;

import com.hierarchyhub.model.OrgDataRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BulkLinkSheetWriterTest {

    @Test
    public void sampleWorkbookHasHeaderAndMasterDataDropdownSource() throws IOException {
        byte[] workbook = ExcelUtils.buildSampleWorkbook(
                List.of(new OrgDataRecord("C1", "Org A"), new OrgDataRecord("C2", "Org B")));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            Sheet referenceSheet = wb.getSheet("referenceSheet");
            assertEquals("parentOrganization", cell(referenceSheet, 0, 0));
            assertEquals("childOrganization", cell(referenceSheet, 0, 1));
            assertEquals(0, referenceSheet.getLastRowNum()); // header only, no data rows

            Sheet masterDataSheet = wb.getSheet("masterData");
            assertEquals("orgId", cell(masterDataSheet, 0, 0));
            assertEquals("organizationName", cell(masterDataSheet, 0, 1));
            assertEquals("C1", cell(masterDataSheet, 1, 0));
            assertEquals("Org A<C1>", cell(masterDataSheet, 1, 1));
        }
    }

    @Test
    public void exportWorkbookWritesOneLevelNamedColumnPerLeafPath() throws IOException {
        List<String> levelHeaderNames = List.of("L1", "L2");
        List<List<String>> leafPathCells = List.of(
                List.of("Ministry<P1>", "Dept<C1>"),
                List.of("Ministry<P1>")); // a leaf sitting directly under the root - shorter than the header row

        byte[] workbook = ExcelUtils.buildExportWorkbook(levelHeaderNames, leafPathCells);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            Sheet referenceSheet = wb.getSheet("referenceSheet");
            assertEquals("L1", cell(referenceSheet, 0, 0));
            assertEquals("L2", cell(referenceSheet, 0, 1));
            assertEquals(2, referenceSheet.getLastRowNum()); // header + 2 leaf rows
            assertEquals("Ministry<P1>", cell(referenceSheet, 1, 0));
            assertEquals("Dept<C1>", cell(referenceSheet, 1, 1));
            assertEquals("Ministry<P1>", cell(referenceSheet, 2, 0));
        }
    }

    @Test
    public void annotatesResultWorkbookWithStatusAndErrorColumns() throws IOException {
        byte[] original = ExcelUtils.buildSampleWorkbook(List.of());
        // add one data row directly, same as an uploaded file would have
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(original))) {
            Row row = wb.getSheet("referenceSheet").createRow(1);
            row.createCell(0).setCellValue("Ministry<P1>");
            row.createCell(1).setCellValue("Dept<C1>");
            original = toBytes(wb);
        }

        byte[] annotated = ExcelUtils.annotateResultWorkbook(original, Map.of(2, "Success"), Map.of());

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(annotated))) {
            Sheet referenceSheet = wb.getSheet("referenceSheet");
            assertEquals("Status", cell(referenceSheet, 0, 2));
            assertEquals("Error", cell(referenceSheet, 0, 3));
            assertEquals("Success", cell(referenceSheet, 1, 2));
            assertTrue(cell(referenceSheet, 1, 3).isEmpty());
        }
    }

    private static String cell(Sheet sheet, int rowIndex, int colIndex) {
        return new DataFormatter().formatCellValue(sheet.getRow(rowIndex).getCell(colIndex));
    }

    private static byte[] toBytes(Workbook wb) throws IOException {
        try (var out = new java.io.ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }
}
