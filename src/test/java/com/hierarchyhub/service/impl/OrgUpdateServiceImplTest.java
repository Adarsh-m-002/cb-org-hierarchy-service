package com.hierarchyhub.service.impl;

import com.hierarchyhub.config.ExternalServiceProperties;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.dto.SyncParentPathResponse;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CassandraUtils;
import org.igot.common.service.OutboundRequestHandlerServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrgUpdateServiceImplTest {

    private final OrgHierarchyRepository orgHierarchyRepository = mock(OrgHierarchyRepository.class);
    private final CassandraUtils cassandraUtils = mock(CassandraUtils.class);
    private final OutboundRequestHandlerServiceImpl outboundRequestHandlerService = mock(OutboundRequestHandlerServiceImpl.class);
    private final ExternalServiceProperties externalServiceProperties = mock(ExternalServiceProperties.class);
    private final AuthUtils authUtils = mock(AuthUtils.class);
    private final ExecutorService parentPathExecutor = Executors.newFixedThreadPool(2);
    private final OrgUpdateServiceImpl service = new OrgUpdateServiceImpl(orgHierarchyRepository, cassandraUtils,
            outboundRequestHandlerService, externalServiceProperties, authUtils, parentPathExecutor);

    private void givenLmsOrgUpdateSucceeds() {
        when(externalServiceProperties.getLmsServiceHost()).thenReturn("http://lms-service:9000");
        when(externalServiceProperties.getLmsOrgUpdateEndpoint()).thenReturn("/private/v1/org/update");
        when(outboundRequestHandlerService.fetchResultUsingPatch(anyString(), any(), any()))
                .thenReturn(Map.of(Constants.RESPONSE_CODE_KEY, Constants.RESPONSE_CODE_OK));
    }

    @Test
    public void resyncParentPathAlwaysDispatchesAnL0RootsOwnUpdateEvenWhenCassandraAlreadyHasAnEmptyParentPathId() {
        givenLmsOrgUpdateSucceeds();
        // a never-linked org's organisation-master row already has parentpathid="" by default - indistinguishable,
        // from this row alone, from "lms-service was already told hierarchyLevel=LevelZero" (it never has been).
        when(orgHierarchyRepository.findById("root-1"))
                .thenReturn(Optional.of(new OrgHierarchyDto("root-1", "Ministry One", null, false, 0)));
        when(orgHierarchyRepository.chainUp("root-1")).thenReturn(List.of("root-1"));
        when(cassandraUtils.fetchBatchOrganisationsFromTable(List.of("root-1")))
                .thenReturn(Map.of("root-1", new OrganisationRecord("root-1", "Ministry One", true, "root-1", 16, "Ministry", "")));

        SyncParentPathResponse result = service.resyncParentPath("root-1", "user-1");

        assertEquals(1, result.getOrgsUpdated());
        assertEquals(0, result.getOrgsSkipped());
        verify(outboundRequestHandlerService).fetchResultUsingPatch(eq("http://lms-service:9000/private/v1/org/update"),
                eq(Collections.singletonMap(Constants.REQUEST, Map.of(
                        Constants.ORGANISATION_ID, "root-1",
                        Constants.PARENT_PATH_ID_KEY, "",
                        Constants.HIERARCHY_LEVEL, "LevelZero",
                        Constants.UPDATED_BY, "user-1"))),
                any());
    }

    @Test
    public void resyncParentPathStillSkipsANonRootOrgWhoseParentPathIdAlreadyMatches() {
        when(orgHierarchyRepository.findById("child-1"))
                .thenReturn(Optional.of(new OrgHierarchyDto("child-1", "Dept One", "root-1", false, 0)));
        when(orgHierarchyRepository.chainUp("child-1")).thenReturn(List.of("child-1", "root-1"));
        when(cassandraUtils.fetchBatchOrganisationsFromTable(List.of("child-1")))
                .thenReturn(Map.of("child-1", new OrganisationRecord("child-1", "Dept One", true, "root-1", 16, "Ministry", "root-1")));

        SyncParentPathResponse result = service.resyncParentPath("child-1", "user-1");

        assertEquals(0, result.getOrgsUpdated());
        assertEquals(1, result.getOrgsSkipped());
        verify(outboundRequestHandlerService, never()).fetchResultUsingPatch(anyString(), any(), any());
    }
}
