package com.blockhost.core.mods;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionPredicateTest {

    private static boolean m(String v, String r) { return VersionPredicate.matches(v, r); }

    @Test
    void exactAndAny() {
        assertTrue(m("1.21.4", "1.21.4"));
        assertTrue(m("1.21.4", "=1.21.4"));
        assertFalse(m("1.21.3", "1.21.4"));
        assertTrue(m("1.21.4", "*"));
        assertTrue(m("1.21", "1.21.0"));
    }

    @Test
    void comparisons() {
        assertTrue(m("1.21.4", ">=1.21"));
        assertTrue(m("1.21.4", ">1.21.3"));
        assertFalse(m("1.20.6", ">=1.21"));
        assertTrue(m("1.20.6", "<1.21"));
        assertTrue(m("1.21.4", ">=1.21 <1.22"));
        assertFalse(m("1.22", ">=1.21 <1.22"));
        assertTrue(m("0.16.10", ">=0.15.0"));
        assertTrue(m("0.16.10", ">0.16.9")); // numérico, no alfabético
    }

    @Test
    void tildeCaretAndWildcards() {
        assertTrue(m("1.20.4", "~1.20.1"));
        assertFalse(m("1.21", "~1.20.1"));
        assertFalse(m("1.20.0", "~1.20.1"));
        assertTrue(m("2.9.0", "^2.1"));
        assertFalse(m("3.0.0", "^2.1"));
        assertTrue(m("1.21.4", "1.21.x"));
        assertFalse(m("1.22.0", "1.21.x"));
        assertTrue(m("1.21.4", "1.x"));
    }

    @Test
    void prereleasesAndBuildMetadata() {
        assertTrue(m("0.6.0+mc1.21.4", ">=0.6.0"));
        assertFalse(m("1.0.0-beta.2", ">=1.0.0"));
        assertTrue(m("1.0.0-beta.10", ">1.0.0-beta.2"));
        assertTrue(m("1.21.4", ">=1.21-")); // "1.21-" = desde la primera pre de 1.21
        assertTrue(m("1.21-rc.1", ">=1.21-"));
        assertFalse(m("1.20.6", ">=1.21-"));
    }

    @Test
    void listMeansAnyOf() {
        assertTrue(VersionPredicate.matchesAny("1.20.1", List.of("1.19.x", "1.20.x")));
        assertFalse(VersionPredicate.matchesAny("1.21", List.of("1.19.x", "1.20.x")));
        assertTrue(VersionPredicate.matchesAny("1.21", List.of()));
    }

    @Test
    void nonSemanticVersionsOnlyMatchExactly() {
        assertTrue(m("mc1.21-abc", "mc1.21-abc"));
        assertFalse(m("mc1.21-abc", ">=1.0"));
    }

    @Test
    void mavenRangesFromForge() {
        assertTrue(m("1.20.1", "[1.20.1,1.21)"));
        assertTrue(m("1.20.6", "[1.20,1.21)"));
        assertFalse(m("1.21", "[1.20,1.21)"));
        assertTrue(m("47.3.0", "[47,)"));
        assertFalse(m("46.0.1", "[47,)"));
        assertTrue(m("1.0", "[1.0]"));
        assertFalse(m("1.0.1", "[1.0]"));
        assertTrue(m("2.0", "(,2.0]"));
        assertFalse(m("2.0", "(,2.0)"));
        assertTrue(m("3.5", "[1.0,2.0),[3.0,)"));
        assertFalse(m("2.5", "[1.0,2.0),[3.0,)"));
        assertTrue(m("21.1.77", "[21.1.0-beta,)"));
    }
}
