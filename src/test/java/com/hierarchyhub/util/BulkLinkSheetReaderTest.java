package com.hierarchyhub.util;

import com.hierarchyhub.dto.BulkLinkRow;
import com.hierarchyhub.dto.BulkLinkRowFailure;
import com.hierarchyhub.exception.ServiceException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BulkLinkSheetReaderTest {

    @Test
    public void extractsIdFromAngleBrackets() {
        assertEquals("01425456149399142419", BulkLinkSheetReader.extractId("Delhi Government<01425456149399142419>"));
        assertEquals("01425713869767475221", BulkLinkSheetReader.extractId("UK Trust authority<01425713869767475221>"));
    }

    @Test
    public void fallsBackToRawValueWhenNoAngleBrackets() {
        assertEquals("01425456149399142419", BulkLinkSheetReader.extractId(" 01425456149399142419 "));
    }

    @Test
    public void parsesRowsDedupesChildrenAndKeepsDuplicateParents() throws IOException {
        byte[] file = sheet(new String[][] {
                {"parentOrgId", "childOrgId"},
                {"Delhi Government<P1>", "Org A<C1>"},
                {"Delhi Government<P1>", "Org B<C2>"},      // same parent again: allowed
                {"Delhi Government<P1>", "Org A again<C1>"}, // duplicate childOrgId: skipped
                {"", ""},                                    // blank trailing row: skipped silently
        });

        BulkLinkSheetReader.ParsedSheet parsed = BulkLinkSheetReader.read(new ByteArrayInputStream(file));

        assertEquals(2, parsed.rows().size());
        assertEquals(0, parsed.rowFailures().size());
        assertEquals(1, parsed.duplicateChildRowsSkipped());
        BulkLinkRow first = parsed.rows().get(0);
        assertEquals("P1", first.getParentOrgId());
        assertEquals("C1", first.getChildOrgId());
        assertEquals("P1", parsed.rows().get(1).getParentOrgId());
        assertEquals("C2", parsed.rows().get(1).getChildOrgId());
    }

    @Test
    public void recordsRowFailureForBlankColumn() throws IOException {
        byte[] file = sheet(new String[][] {
                {"parentOrgId", "childOrgId"},
                {"Delhi Government<P1>", ""},
        });

        BulkLinkSheetReader.ParsedSheet parsed = BulkLinkSheetReader.read(new ByteArrayInputStream(file));

        assertEquals(0, parsed.rows().size());
        assertEquals(1, parsed.rowFailures().size());
        BulkLinkRowFailure failure = parsed.rowFailures().get(0);
        assertEquals(Integer.valueOf(2), failure.getRowNumber());
        assertEquals("childOrgId is blank", failure.getReason());
    }

    @Test
    public void rejectsFileWithoutTheExpectedSheet() throws IOException {
        byte[] file = sheetNamed("someOtherSheet", new String[][] {{"parentOrgId", "childOrgId"}});
        try {
            BulkLinkSheetReader.read(new ByteArrayInputStream(file));
            fail("expected a ServiceException");
        } catch (ServiceException e) {
            assertTrue(e.getMessage().contains("referenceSheet"));
        }
    }

    @Test
    public void rejectsSheetMissingRequiredColumns() throws IOException {
        byte[] file = sheet(new String[][] {{"parentOrgId", "somethingElse"}});
        try {
            BulkLinkSheetReader.read(new ByteArrayInputStream(file));
            fail("expected a ServiceException");
        } catch (ServiceException e) {
            assertTrue(e.getMessage().contains("childOrgId"));
        }
    }

    private static byte[] sheet(String[][] rows) throws IOException {
        return sheetNamed("referenceSheet", rows);
    }

    private static byte[] sheetNamed(String sheetName, String[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
