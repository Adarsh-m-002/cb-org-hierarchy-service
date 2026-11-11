package com.hierarchyhub.util;

import com.hierarchyhub.dto.BulkLinkRow;
import com.hierarchyhub.dto.BulkLinkRowFailure;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@code /bulk/link} upload's {@value Constants#BULK_LINK_SHEET_NAME} sheet, entirely in memory (the caller
 * passes a {@code ByteArrayInputStream} over the uploaded bytes; nothing here touches disk). Each data cell under
 * {@value Constants#BULK_LINK_COLUMN_PARENT}/{@value Constants#BULK_LINK_COLUMN_CHILD} is expected as
 * {@code "<display name><orgId>"} (e.g. {@code "Delhi Government<01425456149399142419>"}); the orgId inside the last
 * {@code <...>} is what gets used, falling back to the raw cell text when there is no {@code <...>} at all so a sheet
 * of bare ids still works.
 */
public final class BulkLinkSheetReader {

    private static final Pattern ANGLE_BRACKET_ID = Pattern.compile("<([^<>]+)>\\s*$");

    private BulkLinkSheetReader() {
    }

    public static final class ParsedSheet {
        private final List<BulkLinkRow> rows;
        private final List<BulkLinkRowFailure> rowFailures;
        private final int duplicateChildRowsSkipped;

        ParsedSheet(List<BulkLinkRow> rows, List<BulkLinkRowFailure> rowFailures, int duplicateChildRowsSkipped) {
            this.rows = rows;
            this.rowFailures = rowFailures;
            this.duplicateChildRowsSkipped = duplicateChildRowsSkipped;
        }

        public List<BulkLinkRow> rows() {
            return rows;
        }

        public List<BulkLinkRowFailure> rowFailures() {
            return rowFailures;
        }

        public int duplicateChildRowsSkipped() {
            return duplicateChildRowsSkipped;
        }
    }

    /** @throws ServiceException(INVALID_REQUEST) if the file cannot be read as a spreadsheet, the sheet is missing, or either column header is missing. */
    public static ParsedSheet read(InputStream in) {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = findSheet(workbook);
            return parse(sheet);
        } catch (IOException | org.apache.poi.EncryptedDocumentException | IllegalArgumentException e) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Could not read the uploaded file as an Excel sheet: " + e.getMessage());
        }
    }

    private static Sheet findSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (Constants.BULK_LINK_SHEET_NAME.equalsIgnoreCase(sheet.getSheetName())) {
                return sheet;
            }
        }
        throw new ServiceException(ErrorCode.INVALID_REQUEST,
                "Sheet '" + Constants.BULK_LINK_SHEET_NAME + "' not found in the uploaded file");
    }

    private static ParsedSheet parse(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Sheet '" + Constants.BULK_LINK_SHEET_NAME + "' has no header row");
        }
        Map<String, Integer> columns = headerColumns(header, formatter);
        Integer parentCol = columns.get(Constants.BULK_LINK_COLUMN_PARENT.toLowerCase());
        Integer childCol = columns.get(Constants.BULK_LINK_COLUMN_CHILD.toLowerCase());
        if (parentCol == null || childCol == null) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Sheet '" + Constants.BULK_LINK_SHEET_NAME
                    + "' must have '" + Constants.BULK_LINK_COLUMN_PARENT + "' and '" + Constants.BULK_LINK_COLUMN_CHILD + "' columns");
        }

        List<BulkLinkRow> rows = new ArrayList<>();
        List<BulkLinkRowFailure> rowFailures = new ArrayList<>();
        Map<String, Boolean> seenChildren = new LinkedHashMap<>();
        int duplicatesSkipped = 0;

        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String parentRaw = formatter.formatCellValue(row.getCell(parentCol)).trim();
            String childRaw = formatter.formatCellValue(row.getCell(childCol)).trim();
            if (parentRaw.isEmpty() && childRaw.isEmpty()) {
                continue; // a blank trailing row
            }

            String parentOrgId = extractId(parentRaw);
            String childOrgId = extractId(childRaw);
            int rowNumber = r + 1; // 1-based, matching what a spreadsheet user sees

            if (AppUtil.isBlank(parentOrgId) || AppUtil.isBlank(childOrgId)) {
                rowFailures.add(new BulkLinkRowFailure(rowNumber, parentOrgId, childOrgId,
                        AppUtil.isBlank(parentOrgId) && AppUtil.isBlank(childOrgId) ? "Both parentOrgId and childOrgId are blank"
                                : AppUtil.isBlank(parentOrgId) ? "parentOrgId is blank" : "childOrgId is blank"));
                continue;
            }
            if (seenChildren.put(childOrgId, Boolean.TRUE) != null) {
                duplicatesSkipped++; // childOrgId must be distinct; first occurrence wins, parentOrgId may repeat freely
                continue;
            }
            rows.add(new BulkLinkRow(rowNumber, parentOrgId, childOrgId));
        }
        return new ParsedSheet(rows, rowFailures, duplicatesSkipped);
    }

    private static Map<String, Integer> headerColumns(Row header, DataFormatter formatter) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            String name = formatter.formatCellValue(header.getCell(c)).trim().toLowerCase();
            if (!name.isEmpty()) {
                columns.put(name, c);
            }
        }
        return columns;
    }

    /** The id inside the last {@code <...>} in {@code cellValue}, or the whole trimmed value when there is no {@code <...>}. */
    static String extractId(String cellValue) {
        if (AppUtil.isBlank(cellValue)) {
            return cellValue;
        }
        Matcher matcher = ANGLE_BRACKET_ID.matcher(cellValue);
        return matcher.find() ? matcher.group(1).trim() : cellValue.trim();
    }
}
