package com.hierarchyhub.util;

import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.ServiceException;
import org.igot.common.ApiResponse;
import org.igot.common.auth.AccessTokenValidator;
import org.igot.common.auth.UserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AuthUtilsTest {

    private final AccessTokenValidator accessTokenValidator = mock(AccessTokenValidator.class);
    private final AuthUtils authUtils = new AuthUtils(accessTokenValidator);

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(authUtils, "authorizedRoles", Arrays.asList("STATE_ADMIN", "SPV_ADMIN", "MDO_LEADER"));
    }

    // ---- validateCallerAuthorizationAndFetchUserId (token + role) ----

    @Test
    public void validateCallerAuthorizationAndFetchUserIdAcceptsAnAuthorizedRole() {
        stubTokenAs("user-1", "MDO_LEADER");

        String userId = authUtils.validateCallerAuthorizationAndFetchUserId("token");

        assertEquals("user-1", userId);
    }

    @Test
    public void validateCallerAuthorizationAndFetchUserIdRefusesAnInvalidToken() {
        when(accessTokenValidator.fetchUserDetailsFromToken(anyString(), any(ApiResponse.class))).thenReturn(null);

        try {
            authUtils.validateCallerAuthorizationAndFetchUserId("token");
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.UNAUTHORIZED, e.errorCode());
        }
    }

    @Test
    public void validateCallerAuthorizationAndFetchUserIdRefusesAnUnauthorizedRole() {
        stubTokenAs("user-1", "PUBLIC");

        try {
            authUtils.validateCallerAuthorizationAndFetchUserId("token");
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.FORBIDDEN, e.errorCode());
        }
    }

    // ---- validateCallerAuthenticationAndFetchUserId (token only, no role check - the read APIs) ----

    @Test
    public void validateCallerAuthenticationAndFetchUserIdAcceptsAnyAuthenticatedUser() {
        stubTokenAs("user-1", "PUBLIC");

        String userId = authUtils.validateCallerAuthenticationAndFetchUserId("token");

        assertEquals("user-1", userId);
    }

    @Test
    public void validateCallerAuthenticationAndFetchUserIdRefusesAnInvalidToken() {
        when(accessTokenValidator.fetchUserDetailsFromToken(anyString(), any(ApiResponse.class))).thenReturn(null);

        try {
            authUtils.validateCallerAuthenticationAndFetchUserId("token");
            fail("Expected ServiceException");
        } catch (ServiceException e) {
            assertEquals(ErrorCode.UNAUTHORIZED, e.errorCode());
        }
    }

    private void stubTokenAs(String userId, String... roles) {
        UserDetails userDetails = new UserDetails();
        userDetails.setUserId(userId);
        userDetails.setUserRoles(Arrays.asList(roles));
        when(accessTokenValidator.fetchUserDetailsFromToken(anyString(), any(ApiResponse.class))).thenReturn(userDetails);
    }
}
