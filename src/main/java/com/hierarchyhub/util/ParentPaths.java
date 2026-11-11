package com.hierarchyhub.util;

import com.hierarchyhub.dto.Constants;

import java.util.ArrayList;
import java.util.List;

/** Materializes the {@code parentPathId} ancestor-breadcrumb string the Sunbird Org Update API expects. */
public final class ParentPaths {

    private ParentPaths() {
    }

    public static String of(List<String> above, List<String> rowPath, String separator) {
        List<String> fullPath = new ArrayList<>(sizeOf(above) + sizeOf(rowPath));
        if (above != null) {
            fullPath.addAll(above);
        }
        if (rowPath != null) {
            fullPath.addAll(rowPath);
        }

        // 0 or 1 node in the full chain means no parents at all: an L0 root or an unlinked org.
        if (fullPath.size() <= 1) {
            return Constants.EMPTY_PARENT_PATH;
        }

        // every token except the last (the node's own id) is an ancestor
        return String.join(separator, fullPath.subList(0, fullPath.size() - 1));
    }

    private static int sizeOf(List<String> list) {
        return list == null ? 0 : list.size();
    }
}
