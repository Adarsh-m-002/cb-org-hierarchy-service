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
import com.hierarchyhub.repository.OrgHierarchyLevelRepository;
import com.hierarchyhub.repository.OrgHierarchyRepository;
import com.hierarchyhub.util.AuthUtils;
import com.hierarchyhub.validation.OrgHierarchyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final AuthUtils authUtils = mock(AuthUtils.class);
    private final OrgHierarchyLevelServiceImpl service =
            new OrgHierarchyLevelServiceImpl(orgHierarchyLevelRepository, orgHierarchyRepository, orgHierarchyValidator, authUtils);

    @BeforeEach
    public void setUp() {
        when(authUtils.validateCallerAuthenticationAndFetchUserId(anyString())).thenReturn("user-1");
        when(authUtils.validateCallerAuthorizationAndFetchUserId(anyString())).thenReturn("user-1");
        when(orgHierarchyRepository.findById("root-1")).thenReturn(Optional.of(rootNode()));
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
    public void updateHierarchyLevelsProvisionsDefaultsThenAppliesTheBatchUpdate() {
        when(orgHierarchyLevelRepository.hasLevels("root-1")).thenReturn(false);
        ApiRequest<UpdateHierarchyLevelsRequestBody> request = requestWith(
                new HierarchyLevelItem(1, "Ministry", "Central Government Ministry"),
                new HierarchyLevelItem(2, "Department", "Department under Ministry"));

        ApiResponse response = service.updateHierarchyLevels("root-1", request, "token");

        verify(orgHierarchyLevelRepository).insertDefaultLevels(eq("root-1"), anyString());
        verify(orgHierarchyLevelRepository).batchUpdateLevels(eq("root-1"), eq(request.getRequest().getLevels()), anyString());
        assertEquals(Constants.MSG_LEVELS_UPDATED, response.get("message"));
    }

    @Test
    public void readHierarchyLevelsRejectsAnUnknownRootOrg() {
        when(orgHierarchyRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ServiceException.class, () -> service.readHierarchyLevels("missing", "token"));
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
    public void levelsBlueprintStartsAtTheGivenLevelNotAlwaysL1() {
        Map<Integer, HierarchyLevelRecord> levels = Map.of(
                1, new HierarchyLevelRecord(1, "Ministry", "Central Government Ministry"),
                2, new HierarchyLevelRecord(2, "Department", "Department under Ministry"));

        List<HierarchyLevelItem> blueprint = service.levelsBlueprint(levels, 2);

        assertEquals(9, blueprint.size()); // levels 2..10, never level 1
        assertEquals(2, blueprint.get(0).getLevel());
        assertEquals("Department", blueprint.get(0).getName());
        assertEquals(10, blueprint.get(blueprint.size() - 1).getLevel());
    }

    @Test
    public void levelsBlueprintFallsBackToLPlusLevelForAnUncustomizedLevel() {
        List<HierarchyLevelItem> blueprint = service.levelsBlueprint(Collections.emptyMap(), 3);

        assertEquals("L3", blueprint.get(0).getName());
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
        return new OrgHierarchyDto("root-1", "Root Org", null, false, 1, Instant.now(), "user-1", Instant.now(), "user-1");
    }
}
