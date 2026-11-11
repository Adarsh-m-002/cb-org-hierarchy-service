package com.hierarchyhub.util;

import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import org.igot.common.ApiResponse;
import org.igot.common.auth.AccessTokenValidator;
import org.igot.common.auth.UserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class AuthUtils {

    private final AccessTokenValidator accessTokenValidator;

    /** Only a caller whose token carries one of these roles may link, move, unlink or bulk-link an org. */
    @Value("#{'${org-hierarchy.access.roles}'.split(',')}")
    private List<String> authorizedRoles;

    public AuthUtils(AccessTokenValidator accessTokenValidator) {
        this.accessTokenValidator = accessTokenValidator;
    }

    /** Verifies {@code accessToken} and confirms the caller holds one of {@code org-hierarchy.access.roles}; returns the resolved user id. */
    public String validateCallerAuthorizationAndFetchUserId(String accessToken) {
        UserDetails userDetails = fetchVerifiedUserDetails(accessToken);
        List<String> userRoles = userDetails.getUserRoles();
        if (userRoles == null || Collections.disjoint(userRoles, authorizedRoles)) {
            throw new ServiceException(ErrorCode.FORBIDDEN,
                    "User " + userDetails.getUserId() + " does not have any of the roles required for this operation: "
                            + authorizedRoles);
        }
        return userDetails.getUserId();
    }

    /** Verifies {@code accessToken} only (no role check) and returns the resolved user id - for the read-only APIs. */
    public String validateCallerAuthenticationAndFetchUserId(String accessToken) {
        return fetchVerifiedUserDetails(accessToken).getUserId();
    }

    private UserDetails fetchVerifiedUserDetails(String accessToken) {
        UserDetails userDetails = accessTokenValidator.fetchUserDetailsFromToken(accessToken, new ApiResponse());
        if (userDetails == null || AppUtil.isBlank(userDetails.getUserId())) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED, "Invalid or expired access token");
        }
        return userDetails;
    }
}
