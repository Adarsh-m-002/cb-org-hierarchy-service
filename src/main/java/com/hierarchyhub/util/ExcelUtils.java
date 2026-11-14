package com.hierarchyhub.util;

import com.hierarchyhub.dto.BulkLinkRow;
import com.hierarchyhub.dto.BulkLinkRowFailure;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.OrgDataRecord;
import com.hierarchyhub.model.ParsedBulkLinkSheet;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExcelUtils {

    private static final Pattern ANGLE_BRACKET_ID = Pattern.compile("<([^<>]+)>\\s*$");
    private static final int DATA_ROWS_RESERVED = Constants.BULK_LINK_MASTER_DATA_MAX_ROWS;

    private ExcelUtils() {
    }

    public static ParsedBulkLinkSheet read(InputStream in) {
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

    private static ParsedBulkLinkSheet parse(Sheet sheet) {
        DataFormatter formatter = new DataFormatter();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Sheet '" + Constants.BULK_LINK_SHEET_NAME + "' has no header row");
        }
        Map<String, Integer> columns = headerColumns(header, formatter);
        Integer parentCol = columns.get(Constants.BULK_LINK_PARENT_ORGANIZATION_COLUMN.toLowerCase());
        Integer childCol = columns.get(Constants.BULK_LINK_CHILD_ORGANIZATION_COLUMN.toLowerCase());
        if (parentCol == null || childCol == null) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Sheet '" + Constants.BULK_LINK_SHEET_NAME
                    + "' must have '" + Constants.BULK_LINK_PARENT_ORGANIZATION_COLUMN + "' and '" + Constants.BULK_LINK_CHILD_ORGANIZATION_COLUMN + "' columns");
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
                        AppUtil.isBlank(parentOrgId) && AppUtil.isBlank(childOrgId)
                                ? "Both " + Constants.BULK_LINK_PARENT_ORGANIZATION_COLUMN + " and " + Constants.BULK_LINK_CHILD_ORGANIZATION_COLUMN + " are blank"
                                : AppUtil.isBlank(parentOrgId) ? Constants.BULK_LINK_PARENT_ORGANIZATION_COLUMN + " is blank"
                                : Constants.BULK_LINK_CHILD_ORGANIZATION_COLUMN + " is blank"));
                continue;
            }
            if (seenChildren.put(childOrgId, Boolean.TRUE) != null) {
                duplicatesSkipped++; // childOrgId must be distinct; first occurrence wins, parentOrgId may repeat freely
                continue;
            }
            rows.add(new BulkLinkRow(rowNumber, parentOrgId, childOrgId));
        }
        return new ParsedBulkLinkSheet(rows, rowFailures, duplicatesSkipped);
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

    /** An empty {@code referenceSheet} (header row only) plus a prefilled {@code masterData} dropdown sheet. */
    public static byte[] buildSampleWorkbook(List<OrgDataRecord> masterData) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet referenceSheet = workbook.createSheet(Constants.BULK_LINK_SHEET_NAME);
            writeReferenceHeader(referenceSheet);
            writeMasterDataSheetAndDropdowns(workbook, referenceSheet, masterData);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the bulk-link sample workbook", e);
        }
    }

    public static byte[] buildExportWorkbook(List<String> levelHeaderNames, List<List<String>> leafPathCells) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet referenceSheet = workbook.createSheet(Constants.BULK_LINK_SHEET_NAME);
            Row header = referenceSheet.createRow(0);
            for (int col = 0; col < levelHeaderNames.size(); col++) {
                header.createCell(col).setCellValue(levelHeaderNames.get(col));
            }
            int rowNum = 1;
            for (List<String> pathCells : leafPathCells) {
                Row row = referenceSheet.createRow(rowNum++);
                for (int col = 0; col < pathCells.size(); col++) {
                    row.createCell(col).setCellValue(pathCells.get(col));
                }
            }
            return toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the bulk-link export workbook", e);
        }
    }

    private static void writeReferenceHeader(Sheet referenceSheet) {
        Row header = referenceSheet.createRow(0);
        header.createCell(0).setCellValue(Constants.BULK_LINK_PARENT_ORGANIZATION_COLUMN);
        header.createCell(1).setCellValue(Constants.BULK_LINK_CHILD_ORGANIZATION_COLUMN);
    }

    private static void writeMasterDataSheetAndDropdowns(XSSFWorkbook workbook, XSSFSheet referenceSheet, List<OrgDataRecord> masterData) {
        XSSFSheet masterDataSheet = workbook.createSheet(Constants.BULK_LINK_MASTER_DATA_SHEET_NAME);
        Row header = masterDataSheet.createRow(0);
        header.createCell(0).setCellValue(Constants.ORGANIZATION_ID);
        header.createCell(1).setCellValue(Constants.BULK_LINK_ORGANIZATION_NAME_COLUMN);

        int rowCount = Math.min(masterData.size(), DATA_ROWS_RESERVED);
        for (int i = 0; i < rowCount; i++) {
            OrgDataRecord record = masterData.get(i);
            Row row = masterDataSheet.createRow(i + 1);
            row.createCell(0).setCellValue(record.id());
            row.createCell(1).setCellValue(namedCell(record.name(), record.id()));
        }
        masterDataSheet.setColumnHidden(0, true);

        if (rowCount == 0) {
            return; // no candidates to pick from yet - ship the reference sheet without a dropdown rather than an empty/invalid one
        }

        String dropdownRange = Constants.BULK_LINK_MASTER_DATA_SHEET_NAME + "!$B$2:$B$" + (rowCount + 1);
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(referenceSheet);
        DataValidationConstraint constraint = helper.createFormulaListConstraint(dropdownRange);
        CellRangeAddressList parentChildCells = new CellRangeAddressList(1, DATA_ROWS_RESERVED, 0, 1);
        DataValidation validation = helper.createValidation(constraint, parentChildCells);
        validation.setShowErrorBox(true);
        referenceSheet.addValidationData(validation);
    }

    public static byte[] annotateResultWorkbook(byte[] originalBytes, Map<Integer, String> statusByRowNumber, Map<Integer, String> errorByRowNumber) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(originalBytes))) {
            Sheet referenceSheet = findReferenceSheet(workbook);
            Row header = referenceSheet.getRow(referenceSheet.getFirstRowNum());
            int statusCol = header.getLastCellNum();
            int errorCol = statusCol + 1;
            header.createCell(statusCol).setCellValue(Constants.RESULT_SHEET_STATUS_COLUMN);
            header.createCell(errorCol).setCellValue(Constants.RESULT_SHEET_ERROR_COLUMN);

            for (int r = header.getRowNum() + 1; r <= referenceSheet.getLastRowNum(); r++) {
                Row row = referenceSheet.getRow(r);
                if (row == null) {
                    continue;
                }
                int rowNumber = r + 1;
                String status = statusByRowNumber.get(rowNumber);
                String error = errorByRowNumber.get(rowNumber);
                if (status != null) {
                    row.createCell(statusCol).setCellValue(status);
                }
                if (error != null) {
                    row.createCell(errorCol).setCellValue(error);
                }
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not annotate the bulk-link result workbook", e);
        }
    }

    private static Sheet findReferenceSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (Constants.BULK_LINK_SHEET_NAME.equalsIgnoreCase(sheet.getSheetName())) {
                return sheet;
            }
        }
        throw new IllegalStateException("Sheet '" + Constants.BULK_LINK_SHEET_NAME + "' not found in the uploaded workbook");
    }

    private static String namedCell(String name, String orgId) {
        return (name == null ? "" : name) + "<" + orgId + ">";
    }

    private static byte[] toBytes(XSSFWorkbook workbook) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
