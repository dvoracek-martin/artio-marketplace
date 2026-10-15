package io.artio.marketplace.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SemanticVersionTest {
  @Test
  void comparesNumericAndPrereleaseIdentifiersAccordingToSemver() {
    assertEquals(
        -1,
        SemanticVersion.parse("1.0.0-alpha.9").compareTo(SemanticVersion.parse("1.0.0-alpha.10")));
    assertEquals(
        -1, SemanticVersion.parse("1.0.0-1").compareTo(SemanticVersion.parse("1.0.0-alpha")));
    assertEquals(-1, SemanticVersion.parse("1.0.0-rc.1").compareTo(SemanticVersion.parse("1.0.0")));
    assertEquals(
        0,
        SemanticVersion.parse("1.2.3+build.1").compareTo(SemanticVersion.parse("1.2.3+build.2")));
  }

  @Test
  void supportsUnboundedCoreVersionComponents() {
    assertEquals(
        1,
        SemanticVersion.parse("999999999999999999999999.0.0")
            .compareTo(SemanticVersion.parse("999999999999999999999998.999.999")));
  }

  @Test
  void rejectsLeadingZeroNumericComponentsAndPrereleaseIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("01.0.0"));
    assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("1.0.0-rc.01"));
  }
}
