package com.hierarchyhub.validation;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.dto.OrgLinkErrorDetail;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.OrgHierarchyDto;
import com.hierarchyhub.model.OrganisationRecord;
import com.hierarchyhub.util.AppUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class OrgHierarchyValidator {

    @Value("${org-hierarchy.ministry.check-enabled}")
    private boolean ministryCheckEnabled;

    @Value("${org-hierarchy.ministry.missing}")
    private String missingMinistryPolicy;

    @Value("#{'${org-hierarchy.ministry-org-types}'.split(',')}")
    private List<Integer> ministryOrgTypes;

    @Value("#{'${org-hierarchy.state-org-types}'.split(',')}")
    private List<Integer> stateOrgTypes;

    /** An org cannot be linked or moved under itself. Used for both {@code /link} and {@code /move}. */
    public void validateDistinctEntities(String orgId, String parentId) {
        if (orgId.equals(parentId)) {
            throw new ServiceException(ErrorCode.CYCLE_DETECTED, "An org cannot be its own parent");
        }
    }

    public void requireEligibleToLink(String childId, String parentId, OrganisationRecord child, OrganisationRecord parent) {
        requireFoundAndActive(childId, child);
        requireFoundAndActive(parentId, parent);
        requireNotMinistryOrStateRoot(child);
        requireSameHierarchyType(child, parent);
        if (ministryCheckEnabled) {
            requireSameMinistryOrState(child, parent);
        }
    }

    public void validateMoveEligibilityInTable(String orgId, String newParentId, OrganisationRecord org, OrganisationRecord newParent) {
        requireEligibleToLink(orgId, newParentId, org, newParent);
    }

    private void requireFoundAndActive(String id, OrganisationRecord record) {
        if (record == null) {
            throw new ServiceException(ErrorCode.ORG_NOT_FOUND, "Org " + id + " not found in the organisation master");
        }
        if (!record.active()) {
            throw new ServiceException(ErrorCode.ORG_INACTIVE, "Org " + id + " is inactive");
        }
    }

    /** A ministry or a state is a hierarchy root by definition: it can never be attached under another org. */
    private void requireNotMinistryOrStateRoot(OrganisationRecord org) {
        if (isMinistryOrStateRoot(org)) {
            throw new ServiceException(ErrorCode.IS_AN_L0, "Org " + org.id() + " is a ministry or state (organizationType "
                    + org.organizationType() + "): it cannot be attached under another org");
        }
    }

    private void requireSameHierarchyType(OrganisationRecord child, OrganisationRecord parent) {
        String childHierarchyType = child.ministryOrStateType();
        String parentHierarchyType = hierarchyTypeOf(parent);
        if (childHierarchyType == null || parentHierarchyType == null) {
            return;
        }
        if (!childHierarchyType.equalsIgnoreCase(parentHierarchyType)) {
            throw new ServiceException(ErrorCode.HIERARCHY_TYPE_MISMATCH,
                    "Org " + child.id() + " belongs to the " + childHierarchyType + " hierarchy but parent " + parent.id()
                            + " belongs to the " + parentHierarchyType + " hierarchy");
        }
    }

    public String hierarchyTypeOf(OrganisationRecord org) {
        if (ministryOrgTypes.contains(org.organizationType())) {
            return Constants.HIERARCHY_TYPE_MINISTRY;
        }
        if (stateOrgTypes.contains(org.organizationType())) {
            return Constants.HIERARCHY_TYPE_STATE;
        }
        return org.ministryOrStateType();
    }

    /** True when {@code org}'s own organizationType marks it as a ministry/state hierarchy root. */
    public boolean isMinistryOrStateRoot(OrganisationRecord org) {
        return ministryOrgTypes.contains(org.organizationType()) || stateOrgTypes.contains(org.organizationType());
    }

    /** {@code exists} must be true, or {@code message} is the failure - e.g. "does {@code id} have a row in org_hierarchy". */
    public void requireExisting(boolean exists, String message) {
        if (!exists) {
            throw new ServiceException(ErrorCode.ORG_NOT_FOUND, message);
        }
    }

    /** {@code parentRecord} (the organisation-table record for {@code parentId}) must exist and be active. */
    public void requireActiveParentOrganisation(String parentId, OrganisationRecord parentRecord) {
        if (parentRecord == null) {
            throw new ServiceException(ErrorCode.ORG_NOT_FOUND, String.format(Constants.ERR_PARENT_NOT_FOUND_IN_TABLE, parentId));
        }
        if (!parentRecord.active()) {
            throw new ServiceException(ErrorCode.ORG_INACTIVE, String.format(Constants.ERR_PARENT_IS_INACTIVE, parentId));
        }
    }

    public void requireMinistryOrStateRoot(String id, OrganisationRecord record) {
        if (!isMinistryOrStateRoot(record)) {
            throw new ServiceException(ErrorCode.ORG_NOT_FOUND, String.format(Constants.ERR_PARENT_NOT_IN_HIERARCHY, id));
        }
    }

    /** {@code orgId} cannot be placed under a parent whose ancestor chain ({@code newParentAncestorChain}) already contains it. */
    public void validateCyclePrevention(String orgId, String newParentId, Collection<String> newParentAncestorChain) {
        if (newParentAncestorChain.contains(orgId)) {
            throw new ServiceException(ErrorCode.CYCLE_DETECTED,
                    "Org " + orgId + " cannot be placed under " + newParentId + ": that is one of its own descendants");
        }
    }

    /** {@code orgId} must have zero active children, or it fails with {@code ORGANISATION_HAS_CHILDREN} - the {@code /unlink} safe-mode guard. */
    public void requireNoActiveChildren(String orgId, boolean hasActiveChildren) {
        if (hasActiveChildren) {
            throw new ServiceException(ErrorCode.ORGANISATION_HAS_CHILDREN, String.format(Constants.ERR_ORGANISATION_HAS_CHILDREN, orgId));
        }
    }

    public String hierarchyRootIdOf(OrganisationRecord org) {
        return isMinistryOrStateRoot(org) ? org.id() : org.ministryId();
    }

    private void requireSameMinistryOrState(OrganisationRecord child, OrganisationRecord parent) {
        String childRootId = hierarchyRootIdOf(child);
        String parentRootId = hierarchyRootIdOf(parent);
        if (childRootId == null || parentRootId == null) {
            if (Constants.MINISTRY_MISSING_POLICY_ALLOW.equalsIgnoreCase(missingMinistryPolicy)) {
                return;
            }
            String who = childRootId == null && parentRootId == null
                    ? "Neither " + child.id() + " nor " + parent.id() + " has"
                    : (childRootId == null ? child.id() : parent.id()) + " has no";
            throw new ServiceException(ErrorCode.MINISTRY_MISMATCH, who + " ministry/state in the organisation master");
        }
        if (!Objects.equals(childRootId, parentRootId)) {
            throw new ServiceException(ErrorCode.MINISTRY_MISMATCH,
                    "Org " + child.id() + " belongs to ministry/state " + childRootId + " but the parent " + parent.id()
                            + " belongs to ministry/state " + parentRootId);
        }
    }

    /** True when {@code orgHierarchyDto} already has a (non-blank) parent - i.e. it's currently linked somewhere. */
    public boolean hasParent(OrgHierarchyDto orgHierarchyDto) {
        return orgHierarchyDto != null && StringUtils.hasText(orgHierarchyDto.parentId());
    }

    /** True when {@code orgHierarchyDto} is already linked under exactly {@code candidateParentId} - the idempotent-no-op case. */
    public boolean isAlreadyLinkedTo(OrgHierarchyDto orgHierarchyDto, String candidateParentId) {
        return hasParent(orgHierarchyDto) && orgHierarchyDto.parentId().equals(candidateParentId);
    }

    public void validateAndCategorizeChild(String childId, String parentId, OrganisationRecord parentRecord,
            OrganisationRecord childOrganisationRecord, OrgHierarchyDto existingHierarchyNode,
            Set<String> parentAncestorChain, List<String> newChildIdsToInsert, List<String> newChildNamesToInsert,
            List<String> existingChildIdsToUpdate, List<String> succeededChildIds,
            List<OrgLinkErrorDetail> failureErrors) {

        if (childId.equals(parentId)) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.CYCLE_DETECTED.name(), Constants.ERR_CANNOT_BE_OWN_PARENT));
            return;
        }
        if (childOrganisationRecord == null) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.ORG_NOT_FOUND.name(),
                    String.format(Constants.ERR_ORG_NOT_FOUND_IN_TABLE, childId)));
            return;
        }
        if (!childOrganisationRecord.active()) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.ORG_INACTIVE.name(),
                    String.format(Constants.ERR_ORG_IS_INACTIVE, childId)));
            return;
        }
        if (isMinistryOrStateRoot(childOrganisationRecord)) {
            String childHierarchyType = hierarchyTypeOf(childOrganisationRecord);
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.IS_AN_L0.name(),
                    String.format(Constants.ERR_ORG_IS_ROOT_L0, childId, childHierarchyType, childOrganisationRecord.organizationType())));
            return;
        }

        String childHierarchyType = hierarchyTypeOf(childOrganisationRecord);
        String parentHierarchyType = hierarchyTypeOf(parentRecord);
        if (childHierarchyType != null && parentHierarchyType != null && !childHierarchyType.equalsIgnoreCase(parentHierarchyType)) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.HIERARCHY_TYPE_MISMATCH.name(),
                    String.format(Constants.ERR_HIERARCHY_TYPE_MISMATCH, childId, childHierarchyType, parentId, parentHierarchyType)));
            return;
        }

        String childRootId = hierarchyRootIdOf(childOrganisationRecord);
        String parentRootId = hierarchyRootIdOf(parentRecord);
        if (!Objects.equals(childRootId, parentRootId)) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.MINISTRY_MISMATCH.name(),
                    String.format(Constants.ERR_DYNAMIC_MINISTRY_MISMATCH, childId, childHierarchyType, childRootId,
                            parentId, parentHierarchyType, parentRootId)));
            return;
        }

        if (existingHierarchyNode == null) {
            newChildIdsToInsert.add(childId);
            newChildNamesToInsert.add(AppUtil.defaultIfBlank(childOrganisationRecord.name(), childId));
            succeededChildIds.add(childId);
            return;
        }
        if (isAlreadyLinkedTo(existingHierarchyNode, parentId)) {
            succeededChildIds.add(childId); // idempotent no-op
            return;
        }
        if (hasParent(existingHierarchyNode)) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.ALREADY_HAS_PARENT.name(),
                    String.format(Constants.ERR_ALREADY_HAS_PARENT, childId, existingHierarchyNode.parentId())));
            return;
        }
        if (parentAncestorChain.contains(childId)) {
            failureErrors.add(new OrgLinkErrorDetail(childId, ErrorCode.CYCLE_DETECTED.name(),
                    String.format(Constants.ERR_CYCLE_DETECTED, childId, parentId)));
            return;
        }

        existingChildIdsToUpdate.add(childId);
        succeededChildIds.add(childId);
    }
}
