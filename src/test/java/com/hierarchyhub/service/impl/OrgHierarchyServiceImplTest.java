package com.hierarchyhub.service.impl;

import com.hierarchyhub.config.ExternalServiceProperties;
import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyEvent;
import com.hierarchyhub.dto.LinkOrgRequestBody;
import com.hierarchyhub.dto.OrgHierarchySearchFilter;
import com.hierarchyhub.dto.OrgHierarchySearchRequest;
import com.hierarchyhub.dto.UnmappedOrgsFilter;
import com.hierarchyhub.dto.UnmappedOrgsRequest;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrgHierarchyDtoWithDepth;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.model.PageWithCount;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.service.HierarchyEventPublisher;
import com.hierarchyhub.service.OrgHierarchyLevelService;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CassandraUtils;
import com.hierarchyhub.util.ElasticsearchUtil;
import com.hierarchyhub.util.RedisCache;
import com.hierarchyhub.validation.OrgHierarchyValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrgHierarchyServiceImplTest {

    private final OrgHierarchyRepository orgHierarchyRepository = mock(OrgHierarchyRepository.class);
    private final OrgHierarchyValidator orgHierarchyValidator = mock(OrgHierarchyValidator.class);
    private final AuthUtils authUtils = mock(AuthUtils.class);
    private final CassandraUtils cassandraUtils = mock(CassandraUtils.class);
    private final HierarchyEventPublisher hierarchyEventPublisher = mock(HierarchyEventPublisher.class);
    private final OrgHierarchyLevelService orgHierarchyLevelService = mock(OrgHierarchyLevelService.class);
    private final RedisCache redisCache = mock(RedisCache.class);
    private final ElasticsearchUtil elasticsearchUtil = mock(ElasticsearchUtil.class);
    private final ExternalServiceProperties externalServiceProperties = mock(ExternalServiceProperties.class);
    private final OrgHierarchyServiceImpl service = new OrgHierarchyServiceImpl(orgHierarchyRepository, orgHierarchyValidator,
            authUtils, cassandraUtils, hierarchyEventPublisher, orgHierarchyLevelService, redisCache, elasticsearchUtil,
            externalServiceProperties, 10);

    @BeforeEach
    public void setUp() {
        when(authUtils.validateCallerAuthenticationAndFetchUserId(anyString())).thenReturn("user-1");
        when(orgHierarchyLevelService.levelMapFor(anyString())).thenReturn(Map.of(1, new HierarchyLevelRecord(1, "Department", "desc")));
        when(orgHierarchyLevelService.levelNameOf(any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> "Department");
    }

    // ------------------------------------------------------------------ linkOrgToHierarchy (root-init only)

    @Test
    public void linkOrgToHierarchyWithNoAssociationsPublishesALinkEventForTheNewRootItself() {
        when(authUtils.validateCallerAuthorizationAndFetchUserId("token")).thenReturn("user-1");
        when(cassandraUtils.fetchBatchOrganisationsFromTable(List.of("root-1")))
                .thenReturn(Map.of("root-1", new OrganisationRecord("root-1", "Ministry One", true, "root-1", 16, "Ministry", null)));
        when(orgHierarchyRepository.findById("root-1")).thenReturn(Optional.empty());
        LinkOrgRequestBody body = new LinkOrgRequestBody();
        body.setParentOrgId("root-1");
        body.setAssociations(List.of());
        ApiRequest<LinkOrgRequestBody> request = new ApiRequest<>();
        request.setRequest(body);

        service.linkOrgToHierarchy(request, "token");

        // without this, a brand-new L0 root would never get its own parentPathId="" / hierarchyLevel=LevelZero
        // org-update call - it gets no link/move event of its own otherwise, only its (later) children would.
        ArgumentCaptor<HierarchyEvent> eventCaptor = ArgumentCaptor.forClass(HierarchyEvent.class);
        verify(hierarchyEventPublisher).publishAsync(eventCaptor.capture());
        HierarchyEvent event = eventCaptor.getValue();
        assertEquals("root-1", event.getOrgId());
        assertNull(event.getParentId());
        assertEquals(Constants.EVENT_TYPE_LINK, event.getEventType());
        assertEquals("user-1", event.getUserId());
    }

    // ------------------------------------------------------------------ searchOrgHierarchy

    @Test
    public void searchOrgHierarchyRejectsWhenNeitherParentOrgIdNorExcludeOrgWithChildrenIsGiven() {
        ApiRequest<OrgHierarchySearchRequest> request = requestWith(new OrgHierarchySearchFilter(), null, null, null);

        assertThrows(ServiceException.class, () -> service.searchOrgHierarchy(request, "token"));
    }

    @Test
    public void searchOrgHierarchyRejectsAnUnknownParentOrgId() {
        when(orgHierarchyRepository.chainUp("missing")).thenReturn(List.of());
        OrgHierarchySearchFilter filters = new OrgHierarchySearchFilter();
        filters.setParentOrgId("missing");
        ApiRequest<OrgHierarchySearchRequest> request = requestWith(filters, null, null, null);

        assertThrows(ServiceException.class, () -> service.searchOrgHierarchy(request, "token"));
    }

    @Test
    public void searchOrgHierarchyGeneralSearchPassesParentOrgIdDirectlyWithNoExclusion() {
        when(orgHierarchyRepository.chainUp("root-1")).thenReturn(List.of("root-1"));
        PageWithCount<OrgHierarchyDtoWithDepth> page = new PageWithCount<>(
                List.of(new OrgHierarchyDtoWithDepth(new OrgHierarchyDto("c1", "Health Dept", "root-1", false, 0), 1)), 142L);
        when(orgHierarchyRepository.findDescendantsPageWithCount(eq("root-1"), eq(10), isNull(), isNull(), isNull(), isNull(), eq(0), eq(50)))
                .thenReturn(page);
        OrgHierarchySearchFilter filters = new OrgHierarchySearchFilter();
        filters.setParentOrgId("root-1");
        ApiRequest<OrgHierarchySearchRequest> request = requestWith(filters, null, null, null);

        ApiResponse response = service.searchOrgHierarchy(request, "token");

        assertEquals(142L, ((Number) response.get("count")).longValue());
        assertEquals(0, response.get("page"));
        assertEquals(50, response.get("limit"));
        List<?> items = (List<?>) response.get("organizations");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("c1", item.get("id"));
        assertEquals(1, item.get("level"));
    }

    @Test
    public void searchOrgHierarchyMoveTargetSearchResolvesParentOrgIdFromExcludeOrgWithChildrenAndPrunesIt() {
        when(orgHierarchyRepository.chainUp("moving-org")).thenReturn(List.of("moving-org", "dept-1", "root-1"));
        PageWithCount<OrgHierarchyDtoWithDepth> page = new PageWithCount<>(List.of(), 0L);
        when(orgHierarchyRepository.findDescendantsPageWithCount(eq("root-1"), eq(10), isNull(), isNull(), eq("moving-org"), isNull(), eq(0), eq(50)))
                .thenReturn(page);
        OrgHierarchySearchFilter filters = new OrgHierarchySearchFilter();
        filters.setExcludeOrgWithChildren("moving-org");
        ApiRequest<OrgHierarchySearchRequest> request = requestWith(filters, null, null, null);

        service.searchOrgHierarchy(request, "token");

        verify(orgHierarchyRepository).findDescendantsPageWithCount(eq("root-1"), eq(10), isNull(), isNull(), eq("moving-org"), isNull(), eq(0), eq(50));
        verify(orgHierarchyRepository, never()).chainUp("root-1"); // the moving org's own chain already told us its root is L0 - no second lookup needed
    }

    @Test
    public void searchOrgHierarchyLevelFilterCapsRecursionDepthAndFiltersToThatLevel() {
        when(orgHierarchyRepository.chainUp("root-1")).thenReturn(List.of("root-1"));
        PageWithCount<OrgHierarchyDtoWithDepth> page = new PageWithCount<>(
                List.of(new OrgHierarchyDtoWithDepth(new OrgHierarchyDto("c1", "Directorate", "p1", false, 0), 2)), 1L);
        when(orgHierarchyRepository.findDescendantsPageWithCount(eq("root-1"), eq(2), isNull(), isNull(), isNull(), eq(2), eq(0), eq(50)))
                .thenReturn(page);
        OrgHierarchySearchFilter filters = new OrgHierarchySearchFilter();
        filters.setParentOrgId("root-1");
        filters.setLevel(2);
        ApiRequest<OrgHierarchySearchRequest> request = requestWith(filters, null, null, null);

        service.searchOrgHierarchy(request, "token");

        // depthLimit passed to the repository is capped to the requested level (2), not the service's full maxDepth (10)
        verify(orgHierarchyRepository).findDescendantsPageWithCount(eq("root-1"), eq(2), isNull(), isNull(), isNull(), eq(2), eq(0), eq(50));
    }

    @Test
    public void searchOrgHierarchyPreservesTheRepositorysLevelByLevelRowOrder() {
        when(orgHierarchyRepository.chainUp("root-1")).thenReturn(List.of("root-1"));
        // the repository (ORDER BY depth, lower(name), id) already returns L1 before L2 even though "Alpha" < "Zeta" alphabetically
        PageWithCount<OrgHierarchyDtoWithDepth> page = new PageWithCount<>(List.of(
                new OrgHierarchyDtoWithDepth(new OrgHierarchyDto("l1", "Zeta Department", "root-1", false, 0), 1),
                new OrgHierarchyDtoWithDepth(new OrgHierarchyDto("l2", "Alpha Directorate", "l1", false, 0), 2)), 2L);
        when(orgHierarchyRepository.findDescendantsPageWithCount(eq("root-1"), eq(10), isNull(), isNull(), isNull(), isNull(), eq(0), eq(50)))
                .thenReturn(page);
        OrgHierarchySearchFilter filters = new OrgHierarchySearchFilter();
        filters.setParentOrgId("root-1");
        ApiRequest<OrgHierarchySearchRequest> request = requestWith(filters, null, null, null);

        ApiResponse response = service.searchOrgHierarchy(request, "token");

        List<?> organizations = (List<?>) response.get("organizations");
        assertEquals("l1", ((Map<?, ?>) organizations.get(0)).get("id"));
        assertEquals("l2", ((Map<?, ?>) organizations.get(1)).get("id"));
    }

    // ------------------------------------------------------------------ searchUnmappedOrganisations

    @Test
    public void searchUnmappedOrganisationsExcludesOrgsTheAcidGuardFindsAlreadyMapped() {
        when(externalServiceProperties.getElasticsearchIndexAlias()).thenReturn("org_alias");
        when(elasticsearchUtil.scrollSearch(eq("org_alias"), any(ObjectNode.class), anyInt(), anyInt())).thenReturn(List.of(
                Map.of("id", "o1", "orgName", "Alpha Org", "ministryOrStateId", "root-1", "ministryOrStateName", "State One"),
                Map.of("id", "o2", "orgName", "Beta Org", "ministryOrStateId", "root-1", "ministryOrStateName", "State One")));
        when(orgHierarchyRepository.findActiveMappedIds(any())).thenReturn(java.util.Set.of("o2"));
        UnmappedOrgsRequest body = new UnmappedOrgsRequest();
        body.setFilters(new UnmappedOrgsFilter());
        ApiRequest<UnmappedOrgsRequest> request = new ApiRequest<>();
        request.setRequest(body);

        ApiResponse response = service.searchUnmappedOrganisations(request, "token");

        assertEquals(1L, ((Number) response.get("count")).longValue());
        List<?> items = (List<?>) response.get("organizations");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("o1", item.get("id"));
        assertEquals("Alpha Org", item.get("name"));
    }

    @Test
    public void searchUnmappedOrganisationsPagesBeyondResultsReturnsEmptyNotAnError() {
        when(externalServiceProperties.getElasticsearchIndexAlias()).thenReturn("org_alias");
        when(elasticsearchUtil.scrollSearch(eq("org_alias"), any(ObjectNode.class), anyInt(), anyInt())).thenReturn(List.of(
                Map.of("id", "o1", "orgName", "Alpha Org")));
        when(orgHierarchyRepository.findActiveMappedIds(any())).thenReturn(java.util.Set.of());
        UnmappedOrgsRequest body = new UnmappedOrgsRequest();
        body.setFilters(new UnmappedOrgsFilter());
        body.setPage(5);
        body.setLimit(10);
        ApiRequest<UnmappedOrgsRequest> request = new ApiRequest<>();
        request.setRequest(body);

        ApiResponse response = service.searchUnmappedOrganisations(request, "token");

        assertEquals(1L, ((Number) response.get("count")).longValue());
        List<?> items = (List<?>) response.get("organizations");
        assertEquals(0, items.size());
    }

    private static ApiRequest<OrgHierarchySearchRequest> requestWith(OrgHierarchySearchFilter filters, String query, Integer page, Integer limit) {
        OrgHierarchySearchRequest body = new OrgHierarchySearchRequest();
        body.setFilters(filters);
        body.setQuery(query);
        body.setPage(page);
        body.setLimit(limit);
        ApiRequest<OrgHierarchySearchRequest> request = new ApiRequest<>();
        request.setRequest(body);
        return request;
    }
}
