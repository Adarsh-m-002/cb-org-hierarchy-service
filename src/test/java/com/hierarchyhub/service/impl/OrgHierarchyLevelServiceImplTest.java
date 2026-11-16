package com.hierarchyhub.service.impl;

import com.hierarchyhub.dto.ApiRequest;
import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.HierarchyLevelItem;
import com.hierarchyhub.dto.OrgTreeNode;
import com.hierarchyhub.dto.UpdateHierarchyLevelsRequestBody;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.model.HierarchyLevelRecord;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.repository.OrgHierarchyLevelRepository;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.util.CassandraUtils;
import com.hierarchyhub.validation.OrgHierarchyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OrgHierarchyLevelServiceImplTest {

    private final OrgHierarchyLevelRepository orgHierarchyLevelRepository = mock(OrgHierarchyLevelRepository.class);
    private final OrgHierarchyRepository orgHierarchyRepository = mock(OrgHierarchyRepository.class);
    private final OrgHierarchyValidator orgHierarchyValidator = new OrgHierarchyValidator();
    private final CassandraUtils cassandraUtils = mock(CassandraUtils.class);
    private final AuthUtils authUtils = mock(AuthUtils.class);
    private final OrgHierarchyLevelServiceImpl service =
            new OrgHierarchyLevelServiceImpl(orgHierarchyLevelRepository, orgHierarchyRepository, orgHierarchyValidator, cassandraUtils, authUtils);

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(orgHierarchyValidator, "ministryOrgTypes", Collections.singletonList(16));
        ReflectionTestUtils.setField(orgHierarchyValidator, "stateOrgTypes", Collections.singletonList(2048));
        when(authUtils.validateCallerAuthenticationAndFetchUserId(anyString())).thenReturn("user-1");
        when(authUtils.validateCallerAuthorizationAndFetchUserId(anyString())).thenReturn("user-1");
        when(orgHierarchyRepository.findById("root-1")).thenReturn(Optional.of(rootNode()));
        when(cassandraUtils.fetchBatchOrganisationsFromTable(any())).thenReturn(Map.of("root-1", ministryOrganisationRecord()));
    }

    @Test
    public void ensureDefaultLevelsProvisionedSkipsWhenLevelsAlreadyExist() {
        when(orgHierarchyLevelRepository.hasLevels("root-1")).thenReturn(true);

        service.ensureDefaultLevelsProvisioned("root-1", "user-1");

        verify(orgHierarchyLevelRepository, never()).insertDefaultLevels(anyString(), anyString());
    }

    @Test
    public void ensureDefaultLevelsProvisionedInsertsDefaultsWhenMissing() {
        when(orgHierarchyLevelRepository.hasLevels("root-1")).thenReturn(false);

        service.ensureDefaultLevelsProvisioned("root-1", "user-1");

        verify(orgHierarchyLevelRepository).insertDefaultLevels("root-1", "user-1");
    }

    @Test
    public void updateHierarchyLevelsRejectsAnUnknownRootOrg() {
        when(orgHierarchyRepository.findById("missing")).thenReturn(Optional.empty());
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(new HierarchyLevelItem(1, "Ministry", "desc"));

        assertThrows(ServiceException.class, () -> service.updateHierarchyLevels("missing", request, "token"));
    }

    @Test
    public void updateHierarchyLevelsRejectsANonL0Org() {
        when(orgHierarchyRepository.findById("root-1")).thenReturn(Optional.of(
                new OrgHierarchyDto("root-1", "Dept", "some-parent", false, 0)));
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(new HierarchyLevelItem(1, "Ministry", "desc"));

        assertThrows(ServiceException.class, () -> service.updateHierarchyLevels("root-1", request, "token"));
    }

    @Test
    public void updateHierarchyLevelsRejectsAnIdleOrg() {
        when(orgHierarchyRepository.findById("root-1")).thenReturn(Optional.of(
                new OrgHierarchyDto("root-1", "Root Org", null, true, 0)));
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(new HierarchyLevelItem(1, "Ministry", "desc"));

        assertThrows(ServiceException.class, () -> service.updateHierarchyLevels("root-1", request, "token"));
    }

    @Test
    public void updateHierarchyLevelsRejectsWhenOrganisationTypeIsNotMinistryOrState() {
        when(cassandraUtils.fetchBatchOrganisationsFromTable(any())).thenReturn(Map.of("root-1", nonMinistryOrganisationRecord()));
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(new HierarchyLevelItem(1, "Ministry", "desc"));

        assertThrows(ServiceException.class, () -> service.updateHierarchyLevels("root-1", request, "token"));
    }

    @Test
    public void updateHierarchyLevelsRejectsDuplicateLevelNumbers() {
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(
                new HierarchyLevelItem(1, "Ministry", "desc"), new HierarchyLevelItem(1, "Ministry Again", "desc2"));

        assertThrows(ServiceException.class, () -> service.updateHierarchyLevels("root-1", request, "token"));
    }

    @Test
    public void updateHierarchyLevelsUpsertsTheWholeLevelsArraySortedByLevel() {
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(
                new HierarchyLevelItem(2, "Department", "Department under Ministry"),
                new HierarchyLevelItem(1, "Ministry", "Central Government Ministry"));

        ApiResponse response = service.updateHierarchyLevels("root-1", request, "token");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HierarchyLevelItem>> savedLevels = ArgumentCaptor.forClass(List.class);
        verify(orgHierarchyLevelRepository).upsertLevels(eq("root-1"), savedLevels.capture(), eq("user-1"));
        List<HierarchyLevelItem> sorted = savedLevels.getValue();
        assertEquals(2, sorted.size());
        assertEquals(1, sorted.get(0).getLevel()); // sorted ascending, regardless of the request's own order
        assertEquals("Ministry", sorted.get(0).getName());
        assertEquals(2, sorted.get(1).getLevel());
        assertEquals("Department", sorted.get(1).getName());
        verify(orgHierarchyLevelRepository, never()).insertDefaultLevels(anyString(), anyString());
        assertEquals(Constants.MSG_LEVELS_UPDATED, response.get("message"));
    }

    @Test
    public void readHierarchyLevelsRejectsAnUnknownRootOrg() {
        when(orgHierarchyRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ServiceException.class, () -> service.readHierarchyLevels("missing", "token"));
    }

    @Test
    public void readHierarchyLevelsRejectsANonL0Org() {
        when(orgHierarchyRepository.findById("root-1")).thenReturn(Optional.of(
                new OrgHierarchyDto("root-1", "Dept", "some-parent", false, 0)));

        assertThrows(ServiceException.class, () -> service.readHierarchyLevels("root-1", "token"));
    }

    @Test
    public void readHierarchyLevelsReturnsEveryConfiguredLevelWithTheFixedMaxAllowedLevel() {
        when(orgHierarchyLevelRepository.findLevels("root-1")).thenReturn(List.of(
                new HierarchyLevelRecord(1, "Ministry", "Central Government Ministry"),
                new HierarchyLevelRecord(2, "Department", "Department under Ministry")));

        ApiResponse response = service.readHierarchyLevels("root-1", "token");

        assertEquals("root-1", response.get("rootOrgId"));
        assertEquals(10, response.get("maxAllowedLevel"));
        assertEquals(2, ((List<?>) response.get("levels")).size());
    }

    @Test
    public void findLevelsDelegatesDirectlyToTheRepository() {
        List<HierarchyLevelRecord> levels = Arrays.asList(new HierarchyLevelRecord(1, "L1", "LevelOne"));
        when(orgHierarchyLevelRepository.findLevels("root-1")).thenReturn(levels);

        assertEquals(levels, service.findLevels("root-1"));
    }

    @Test
    public void levelsBlueprintReturnsOnlyStoredLevelsAtOrAboveStartLevel() {
        Map<Integer, HierarchyLevelRecord> levels = Map.of(
                1, new HierarchyLevelRecord(1, "Ministry", "Central Government Ministry"),
                3, new HierarchyLevelRecord(3, "Directorate", "Autonomous Board or Directorate"),
                5, new HierarchyLevelRecord(5, "Field Unit", "District Office or Field Unit"));

        List<HierarchyLevelItem> blueprint = service.levelsBlueprint(levels, 3);

        assertEquals(2, blueprint.size()); // only the stored levels 3 and 5 - level 1 excluded, no padding up to 10
        assertEquals(3, blueprint.get(0).getLevel());
        assertEquals("Directorate", blueprint.get(0).getName());
        assertEquals(5, blueprint.get(1).getLevel());
    }

    @Test
    public void levelsBlueprintIsEmptyWhenNothingIsStoredForThatRoot() {
        List<HierarchyLevelItem> blueprint = service.levelsBlueprint(Collections.emptyMap(), 3);

        assertTrue(blueprint.isEmpty()); // DB-truth only - never fabricates an "L3" fallback entry
    }

    @Test
    public void levelNameOfReturnsNullRatherThanAFabricatedFallbackWhenNotStored() {
        assertNull(service.levelNameOf(Collections.emptyMap(), 7));
    }

    @Test
    public void maxLevelInReturnsTheDeepestLevelReachedInTheSubtree() {
        OrgTreeNode leaf = new OrgTreeNode("c1", "Child", false, 0, 3, "L3", Collections.emptyList());
        OrgTreeNode root = new OrgTreeNode("root-1", "Root Org", false, 1, 2, "L2", List.of(leaf));

        assertEquals(3, service.maxLevelIn(root));
    }

    private static ApiRequest<UpdateHierarchyLevelsRequestBody> requestWith(HierarchyLevelItem... items) {
        UpdateHierarchyLevelsRequestBody body = new UpdateHierarchyLevelsRequestBody();
        body.setLevels(Arrays.asList(items));
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = new ApiRequest<>();
        request.setRequest(body);
        return request;
    }

    private static OrgHierarchyDto rootNode() {
        return new OrgHierarchyDto("root-1", "Root Org", null, false, 1);
    }

    private static OrganisationRecord ministryOrganisationRecord() {
        return new OrganisationRecord("root-1", "Root Org", true, null, 16, "ministry", null);
    }

    private static OrganisationRecord nonMinistryOrganisationRecord() {
        return new OrganisationRecord("root-1", "Root Org", true, "ministry-1", 1, "ministry", null);
    }
}
