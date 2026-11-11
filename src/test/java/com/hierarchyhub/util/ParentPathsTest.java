package com.hierarchyhub.util;

import com.hierarchyhub.dto.Constants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ParentPathsTest {

    @Test
    public void returnsEmptyPathForAnL0RootWithNoAncestors() {
        String path = ParentPaths.of(Collections.emptyList(), Collections.singletonList("org-root"), Constants.PATH_SEPARATOR);

        assertEquals(Constants.EMPTY_PARENT_PATH, path);
    }

    @Test
    public void returnsEmptyPathWhenBothChainsAreEmpty() {
        String path = ParentPaths.of(Collections.emptyList(), Collections.emptyList(), Constants.PATH_SEPARATOR);

        assertEquals(Constants.EMPTY_PARENT_PATH, path);
    }

    @Test
    public void joinsAboveAncestorsAndRowPathExcludingTheNodeItself() {
        List<String> above = Arrays.asList("org-l0", "org-l1");
        List<String> rowPath = Arrays.asList("org-l2", "org-leaf");

        String path = ParentPaths.of(above, rowPath, Constants.PATH_SEPARATOR);

        assertEquals("org-l0_org-l1_org-l2", path);
    }

    @Test
    public void buildsTheFullChainFromRowPathAloneWhenThereIsNoAbovePrefix() {
        List<String> rootFirstChain = Arrays.asList("org-l0", "org-l1", "org-leaf");

        String path = ParentPaths.of(Collections.emptyList(), rootFirstChain, Constants.PATH_SEPARATOR);

        assertEquals("org-l0_org-l1", path);
    }

    @Test
    public void treatsNullChainsAsEmpty() {
        String path = ParentPaths.of(null, Collections.singletonList("org-root"), Constants.PATH_SEPARATOR);

        assertEquals(Constants.EMPTY_PARENT_PATH, path);
    }

    @Test
    public void joinsWithTheGivenSeparator() {
        List<String> above = Collections.singletonList("org-l0");
        List<String> rowPath = Arrays.asList("org-l1", "org-leaf");

        String path = ParentPaths.of(above, rowPath, "-");

        assertEquals("org-l0-org-l1", path);
    }
}
