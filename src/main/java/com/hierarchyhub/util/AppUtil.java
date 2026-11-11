package com.hierarchyhub.util;

import com.hierarchyhub.model.ApiResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small string helpers reused across the service/validation layer (no Spring dependency, plain statics). */
public final class AppUtil {

    private static final char CURSOR_SEPARATOR = '\u0001';
    private static final Pattern VERSION_SEGMENT = Pattern.compile("/(v\\d+)(?:/|$)");

    private AppUtil() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** {@code value} if it is not blank, otherwise {@code fallback}. */
    public static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    /** Opaque keyset-pagination cursor encoding a page's last (name, id). */
    public static String encodeCursor(String name, String id) {
        String raw = name + CURSOR_SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes a cursor from {@link #encodeCursor}; {@code [0]} is the name, {@code [1]} is the id. */
    public static String[] decodeCursor(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int separator = raw.indexOf(CURSOR_SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("Malformed cursor");
        }
        return new String[] {raw.substring(0, separator), raw.substring(separator + 1)};
    }

    /** Pulls the {@code vN} path segment out of a request URI (e.g. "/org/v2/hierarchy/link" -> "v2"), or null if there is none. */
    public static String apiVersionFromUri(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher matcher = VERSION_SEGMENT.matcher(uri);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Wraps {@code result} in the standard success envelope, reporting whatever {@code vN} segment the actual request path carries (falls back to ApiResponse's "v1" default when there is none, e.g. no request is bound to the current thread). */
    public static ApiResponse versionedResponse(String apiId, Object result) {
        ApiResponse response = ApiResponse.success(apiId, result);
        String version = apiVersionFromUri(currentRequestUri());
        if (version != null) {
            response.setVer(version);
        }
        return response;
    }

    private static String currentRequestUri() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest().getRequestURI();
    }

    /**
     * {@code raw} as a case-insensitive {@code LIKE ... ESCAPE '\'} "contains" pattern: {@code \}, {@code %} and
     * {@code _} are escaped first so a literal {@code %}/{@code _} typed by the caller is matched literally, not
     * treated as a wildcard, then the whole thing is wrapped in {@code %...%}.
     */
    public static String likeContainsPattern(String raw) {
        return "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
