package com.hierarchyhub.validation;

import com.hierarchyhub.dto.OrgLinkErrorDetail;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrganisationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link OrgHierarchyValidator} is a pure checker - no repository/Cassandra dependency, no mocking needed here:
 * every test just builds the input data it wants and calls a validator method directly.
 */
public class OrgHierarchyValidatorTest {

    private final OrgHierarchyValidator validator = new OrgHierarchyValidator();

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(validator, "ministryCheckEnabled", true);
        ReflectionTestUtils.setField(validator, "missingMinistryPolicy", "BLOCK");
        ReflectionTestUtils.setField(validator, "ministryOrgTypes", Collections.singletonList(16));
        ReflectionTestUtils.setField(validator, "stateOrgTypes", Collections.singletonList(2048));
    }

    // ---- validateDistinctEntities (shared by /link and /move) ----

    @Test
    public void requireDistinctAcceptsTwoDifferentIds() {
        validator.validateDistinctEntities("child-1", "parent-1");
    }

    @Test
    public void requireDistinctRefusesAnOrgAsItsOwnParent() {
        try {
            validator.validateDistinctEntities("org-1", "org-1");
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.CYCLE_DETECTED, e.errorCode());
        }
    }

    // ---- requireEligibleToLink: existence, active status, ministry match (unchanged rules) ----

    @Test
    public void allowsLinkingWithinTheSameMinistry() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", 128, "state");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        validator.requireEligibleToLink("child-1", "parent-1", child, parent);
    }

    @Test
    public void refusesAcrossMinistries() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", 128, "state");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-2", 128, "state");

        try {
            validator.requireEligibleToLink("child-1", "parent-1", child, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.MINISTRY_MISMATCH, e.errorCode());
        }
    }

    @Test
    public void refusesWhenOrgIsMissingFromTheMaster() {
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        try {
            validator.requireEligibleToLink("child-missing", "parent-1", null, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORG_NOT_FOUND, e.errorCode());
        }
    }

    @Test
    public void refusesAnInactiveOrg() {
        OrganisationRecord child = organisation("child-1", false, "ministry-1", 128, "state");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        try {
            validator.requireEligibleToLink("child-1", "parent-1", child, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORG_INACTIVE, e.errorCode());
        }
    }

    @Test
    public void allowsAMissingMinistryWhenThePolicyIsAllow() {
        ReflectionTestUtils.setField(validator, "missingMinistryPolicy", "ALLOW");
        OrganisationRecord child = organisation("child-1", true, null, 128, "state");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        validator.requireEligibleToLink("child-1", "parent-1", child, parent);
    }

    // ---- requireEligibleToLink: ministry/state root protection and hierarchy-type match (new rules) ----

    @Test
    public void refusesLinkingAMinistryUnderAnotherOrg() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", 16, "SPV");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "ministry");

        try {
            validator.requireEligibleToLink("child-1", "parent-1", child, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.IS_AN_L0, e.errorCode());
        }
    }

    @Test
    public void refusesLinkingAStateUnderAnotherOrg() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", 2048, "SPV");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        try {
            validator.requireEligibleToLink("child-1", "parent-1", child, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.IS_AN_L0, e.errorCode());
        }
    }

    @Test
    public void allowsAttachingDirectlyUnderAMinistryRoot() {
        // A root's own ministryorstateid column does not point back to itself - it is unrelated/absent - so the
        // only way a direct child can match it is by naming the root's own id, not the root's ministryId value.
        OrganisationRecord child = organisation("child-1", true, "parent-1", 128, "ministry");
        OrganisationRecord parent = organisation("parent-1", true, "unrelated-root-id", 16, "SPV");

        validator.requireEligibleToLink("child-1", "parent-1", child, parent);
    }

    @Test
    public void refusesAttachingDirectlyUnderAMinistryRootWhenChildNamesTheWrongRoot() {
        OrganisationRecord child = organisation("child-1", true, "some-other-root-id", 128, "ministry");
        OrganisationRecord parent = organisation("parent-1", true, "unrelated-root-id", 16, "SPV");

        try {
            validator.requireEligibleToLink("child-1", "parent-1", child, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.MINISTRY_MISMATCH, e.errorCode());
        }
    }

    @Test
    public void refusesAttachingAMinistrySubOrgUnderAStateSubOrg() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", 128, "ministry");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        try {
            validator.requireEligibleToLink("child-1", "parent-1", child, parent);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.HIERARCHY_TYPE_MISMATCH, e.errorCode());
        }
    }

    @Test
    public void allowsAttachingAStateSubOrgUnderAnotherStateSubOrg() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", 128, "state");
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        validator.requireEligibleToLink("child-1", "parent-1", child, parent);
    }

    @Test
    public void skipsTheHierarchyTypeCheckWhenEitherSideHasNoRecordedType() {
        OrganisationRecord child = organisation("child-1", true, "ministry-1", null, null);
        OrganisationRecord parent = organisation("parent-1", true, "ministry-1", 128, "state");

        validator.requireEligibleToLink("child-1", "parent-1", child, parent);
    }

    // ---- requireExisting ----

    @Test
    public void requireExistingAcceptsAMappedOrg() {
        validator.requireExisting(true, "org-1 is not in the hierarchy");
    }

    @Test
    public void requireExistingRefusesAnUnmappedOrg() {
        try {
            validator.requireExisting(false, "org-1 is not in the hierarchy");
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORG_NOT_FOUND, e.errorCode());
        }
    }

    // ---- requireActiveParentOrganisation / requireMinistryOrStateRoot (parent-must-already-be-mapped rule, with one exception) ----

    @Test
    public void requireActiveParentOrganisationAcceptsAnActiveRecord() {
        validator.requireActiveParentOrganisation("parent-1", organisation("parent-1", true, "ministry-1", 128, "state"));
    }

    @Test
    public void requireActiveParentOrganisationRefusesAMissingRecord() {
        try {
            validator.requireActiveParentOrganisation("parent-1", null);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORG_NOT_FOUND, e.errorCode());
        }
    }

    @Test
    public void requireActiveParentOrganisationRefusesAnInactiveRecord() {
        try {
            validator.requireActiveParentOrganisation("parent-1", organisation("parent-1", false, "ministry-1", 128, "state"));
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORG_INACTIVE, e.errorCode());
        }
    }

    @Test
    public void requireMinistryOrStateRootAcceptsAMinistry() {
        validator.requireMinistryOrStateRoot("parent-1", organisation("parent-1", true, "unrelated-root-id", 16, "SPV"));
    }

    @Test
    public void requireMinistryOrStateRootRefusesANonRootOrg() {
        try {
            validator.requireMinistryOrStateRoot("parent-1", organisation("parent-1", true, "ministry-1", 128, "ministry"));
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORG_NOT_FOUND, e.errorCode());
        }
    }

    // ---- validateCyclePrevention ----

    @Test
    public void requireNoCycleAcceptsAParentThatIsNotADescendant() {
        validator.validateCyclePrevention("org-1", "new-parent", Arrays.asList("new-parent", "grandparent"));
    }

    @Test
    public void requireNoCycleRefusesMovingUnderOwnDescendant() {
        try {
            validator.validateCyclePrevention("org-1", "descendant", Arrays.asList("descendant", "org-1", "root"));
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.CYCLE_DETECTED, e.errorCode());
        }
    }

    // ---- requireNoActiveChildren (/unlink safe-mode guard) ----

    @Test
    public void requireNoActiveChildrenAcceptsALeaf() {
        validator.requireNoActiveChildren("org-1", false);
    }

    @Test
    public void requireNoActiveChildrenRefusesAnOrgWithChildren() {
        try {
            validator.requireNoActiveChildren("org-1", true);
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.ORGANISATION_HAS_CHILDREN, e.errorCode());
        }
    }

    // ---- hasParent / isAlreadyLinkedTo ----

    @Test
    public void hasParentIsFalseForNullOrUnlinked() {
        assertFalse(validator.hasParent(null));
        assertFalse(validator.hasParent(node("org-1", null)));
    }

    @Test
    public void hasParentIsTrueWhenLinked() {
        assertTrue(validator.hasParent(node("org-1", "parent-1")));
    }

    @Test
    public void isAlreadyLinkedToMatchesOnlyTheExactParent() {
        OrgHierarchyDto linked = node("org-1", "parent-1");
        assertTrue(validator.isAlreadyLinkedTo(linked, "parent-1"));
        assertFalse(validator.isAlreadyLinkedTo(linked, "parent-2"));
        assertFalse(validator.isAlreadyLinkedTo(node("org-1", null), "parent-1"));
    }

    // ---- validateAndCategorizeChild (/link's per-child batch guard) ----

    @Test
    public void categorizesANewChildAsAnInsert() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-1", 128, "state"), null, Collections.emptySet());

        assertEquals(Collections.singletonList("child-1"), result.newChildIdsToInsert);
        assertEquals(Collections.singletonList("child-1"), result.succeededChildIds);
        assertTrue(result.failureErrors.isEmpty());
    }

    @Test
    public void categorizesAnExistingUnlinkedChildAsAnUpdate() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-1", 128, "state"), node("child-1", null), Collections.emptySet());

        assertEquals(Collections.singletonList("child-1"), result.existingChildIdsToUpdate);
        assertEquals(Collections.singletonList("child-1"), result.succeededChildIds);
        assertTrue(result.failureErrors.isEmpty());
    }

    @Test
    public void categorizesAChildAlreadyUnderTheSameParentAsAnIdempotentSuccess() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-1", 128, "state"), node("child-1", "parent-1"), Collections.emptySet());

        assertEquals(Collections.singletonList("child-1"), result.succeededChildIds);
        assertTrue(result.newChildIdsToInsert.isEmpty());
        assertTrue(result.existingChildIdsToUpdate.isEmpty());
        assertTrue(result.failureErrors.isEmpty());
    }

    @Test
    public void refusesASelfParentingChild() {
        CategorizationResult result = categorize("org-1", "org-1", organisation("org-1", true, "ministry-1", 128, "state"),
                organisation("org-1", true, "ministry-1", 128, "state"), null, Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.CYCLE_DETECTED.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAChildMissingFromTheOrganisationTable() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"), null, null, Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.ORG_NOT_FOUND.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAnInactiveChild() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", false, "ministry-1", 128, "state"), null, Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.ORG_INACTIVE.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAMinistryOrStateRootAsAChild() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "unrelated-root-id", 16, "SPV"), null, Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.IS_AN_L0.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAHierarchyTypeMismatch() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-1", 128, "ministry"), null, Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.HIERARCHY_TYPE_MISMATCH.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAMinistryStateBoundaryMismatch() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-2", 128, "state"), null, Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.MINISTRY_MISMATCH.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAChildAlreadyUnderADifferentParent() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-1", 128, "state"), node("child-1", "other-parent"), Collections.emptySet());

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.ALREADY_HAS_PARENT.name(), result.failureErrors.get(0).getErr());
    }

    @Test
    public void refusesAChildThatIsAnAncestorOfTheParent() {
        CategorizationResult result = categorize("child-1", "parent-1",
                organisation("parent-1", true, "ministry-1", 128, "state"),
                organisation("child-1", true, "ministry-1", 128, "state"), node("child-1", null),
                new HashSet<>(Collections.singletonList("child-1")));

        assertEquals(1, result.failureErrors.size());
        assertEquals(ErrorCode.CYCLE_DETECTED.name(), result.failureErrors.get(0).getErr());
    }

    private CategorizationResult categorize(String childId, String parentId, OrganisationRecord parentRecord,
            OrganisationRecord childRecord, OrgHierarchyDto existingHierarchyNode, Set<String> parentAncestorChain) {
        CategorizationResult result = new CategorizationResult();
        validator.validateAndCategorizeChild(childId, parentId, parentRecord, childRecord, existingHierarchyNode,
                parentAncestorChain, result.newChildIdsToInsert, result.newChildNamesToInsert,
                result.existingChildIdsToUpdate, result.succeededChildIds, result.failureErrors);
        return result;
    }

    private static final class CategorizationResult {
        final List<String> newChildIdsToInsert = new ArrayList<>();
        final List<String> newChildNamesToInsert = new ArrayList<>();
        final List<String> existingChildIdsToUpdate = new ArrayList<>();
        final List<String> succeededChildIds = new ArrayList<>();
        final List<OrgLinkErrorDetail> failureErrors = new ArrayList<>();
    }

    private static OrganisationRecord organisation(String id, boolean active, String ministryId, Integer organizationType, String ministryOrStateType) {
        return new OrganisationRecord(id, "Name of " + id, active, ministryId, organizationType, ministryOrStateType, null);
    }

    private static OrgHierarchyDto node(String id, String parentId) {
        return new OrgHierarchyDto(id, "Name", parentId, false, 0, null, null, null, null);
    }
}
