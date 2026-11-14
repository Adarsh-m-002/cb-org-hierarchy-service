package com.hierarchyhub.service.impl;

import com.hierarchyhub.dto.BulkLinkUploadEvent;
import com.hierarchyhub.dto.BulkUploadJobResponse;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrgDataRecord;
import com.hierarchyhub.model.ParsedBulkLinkSheet;
import com.hierarchyhub.producer.KafkaProducer;
import com.hierarchyhub.repository.BulkUploadCassandraRepository;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.BulkLinkFileService;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.service.OrgHierarchyService;
import com.hierarchyhub.util.AppUtil;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.ExcelUtils;
import com.hierarchyhub.util.CloudStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class BulkLinkFileServiceImpl implements BulkLinkFileService {

    private static final Logger log = LoggerFactory.getLogger(BulkLinkFileServiceImpl.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX").withZone(ZoneOffset.UTC);

    private final AuthUtils authUtils;
    private final CloudStorageService cloudStorageService;
    private final BulkUploadCassandraRepository bulkUploadCassandraRepository;
    private final KafkaProducer kafkaProducer;
    private final OrgHierarchyRepository orgHierarchyRepository;
    private final OrgHierarchyService orgHierarchyService;
    private final OrgHierarchyLevelService orgHierarchyLevelService;

    @Value("${org-hierarchy.bulk.max-rows}")
    private int maxRows;

    @Value("${org-hierarchy.bulk.upload.kafka.topic}")
    private String bulkUploadTopic;

    public BulkLinkFileServiceImpl(AuthUtils authUtils, CloudStorageService cloudStorageService,
                                    BulkUploadCassandraRepository bulkUploadCassandraRepository, KafkaProducer kafkaProducer,
                                    OrgHierarchyRepository orgHierarchyRepository, OrgHierarchyService orgHierarchyService,
                                    OrgHierarchyLevelService orgHierarchyLevelService) {
        this.authUtils = authUtils;
        this.cloudStorageService = cloudStorageService;
        this.bulkUploadCassandraRepository = bulkUploadCassandraRepository;
        this.kafkaProducer = kafkaProducer;
        this.orgHierarchyRepository = orgHierarchyRepository;
        this.orgHierarchyService = orgHierarchyService;
        this.orgHierarchyLevelService = orgHierarchyLevelService;
    }

    @Override
    public byte[] buildSampleWorkbook(String rootOrgId, String accessToken) {
        authUtils.validateCallerAuthenticationAndFetchUserId(accessToken);
        List<OrgDataRecord> masterData = orgHierarchyService.getMasterData(rootOrgId);
        return ExcelUtils.buildSampleWorkbook(masterData);
    }

    @Override
    public byte[] buildExportWorkbook(String rootOrgId, String accessToken) {
        authUtils.validateCallerAuthenticationAndFetchUserId(accessToken);
        List<OrgHierarchyDto> subtree = orgHierarchyRepository.findSubtree(rootOrgId);
        if (subtree.isEmpty()) {
            throw new ServiceException(ErrorCode.ORG_NOT_FOUND, "Org not found in hierarchy: " + rootOrgId);
        }

        Map<String, String> nameById = new HashMap<>(subtree.size());
        Set<String> leafIds = new HashSet<>();
        for (OrgHierarchyDto node : subtree) {
            nameById.put(node.id(), node.name());
            if (node.childCount() == 0) {
                leafIds.add(node.id());
            }
        }

        List<List<String>> leafPathCells = new ArrayList<>();
        orgHierarchyRepository.streamSubtreeWithPaths(rootOrgId, (node, depth, pathIds) -> {
            if (!leafIds.contains(node.id())) {
                return;
            }
            List<String> cells = new ArrayList<>(pathIds.size() - 1);
            for (int i = 1; i < pathIds.size(); i++) { // pathIds[0] is rootOrgId itself - not its own level column
                String id = pathIds.get(i);
                cells.add(namedCell(nameById.get(id), id));
            }
            leafPathCells.add(cells);
        });

        List<String> levelHeaderNames = levelHeaderNames(rootOrgId);
        return ExcelUtils.buildExportWorkbook(levelHeaderNames, leafPathCells);
    }

    /** Every configured level's own name (level 1 first) - always the full configured set, same as sunbird-cb-ext always showing every framework category regardless of how deep any one branch actually goes. */
    private List<String> levelHeaderNames(String rootOrgId) {
        Map<Integer, HierarchyLevelRecord> levels = new TreeMap<>(orgHierarchyLevelService.levelMapFor(rootOrgId));
        List<String> names = new ArrayList<>(levels.size());
        for (HierarchyLevelRecord level : levels.values()) {
            names.add(level.name());
        }
        return names;
    }

    private static String namedCell(String name, String orgId) {
        return (name == null ? "" : name) + "<" + orgId + ">";
    }

    @Override
    public ApiResponse initiate(MultipartFile file, String rootOrgId, String accessToken) {
        String userId = authUtils.validateCallerAuthorizationAndFetchUserId(accessToken);
        if (file == null || file.isEmpty()) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Upload the Excel file in the form field 'file'");
        }
        if (AppUtil.isBlank(rootOrgId)) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "rootOrgId is required");
        }

        byte[] fileBytes = bytesOf(file);
        ParsedBulkLinkSheet parsedSheet = ExcelUtils.read(new ByteArrayInputStream(fileBytes));
        int totalRows = parsedSheet.rows().size() + parsedSheet.rowFailures().size();
        if (totalRows > maxRows) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST,
                    "At most " + maxRows + " rows per file; split it into smaller files");
        }

        String identifier = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename();
        String objectKey = cloudStorageService.newObjectKey(fileName);
        cloudStorageService.upload(fileBytes, objectKey);

        String dateCreatedOn = TIMESTAMP.format(Instant.now());
        bulkUploadCassandraRepository.insertInitiatedJob(rootOrgId, identifier, fileName, objectKey, userId);

        kafkaProducer.pushWithKey(bulkUploadTopic,
                new BulkLinkUploadEvent(rootOrgId, identifier, fileName, objectKey, userId), rootOrgId);
        log.info("bulkLink initiated: identifier={}, fileName={}, rootOrgId={}, totalRows={}, parseRowFailures={}, duplicateChildRowsSkipped={}",
                identifier, fileName, rootOrgId, totalRows, parsedSheet.rowFailures().size(), parsedSheet.duplicateChildRowsSkipped());

        BulkUploadJobResponse response = new BulkUploadJobResponse(identifier, fileName, userId, dateCreatedOn, rootOrgId, Constants.INITIATED);
        return AppUtil.versionedResponse(Constants.API_BULK_LINK, response);
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ServiceException(ErrorCode.INVALID_REQUEST, "Could not read the uploaded file: " + e.getMessage());
        }
    }
}
