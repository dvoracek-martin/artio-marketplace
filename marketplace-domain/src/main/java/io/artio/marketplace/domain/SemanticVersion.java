package io.artio.marketplace.domain;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;

/** SemVer 2.0 parser/comparator used for discovery; runtime manifests stay Core-owned. */
public record SemanticVersion(
    BigInteger major, BigInteger minor, BigInteger patch, List<String> prerelease)
    implements Comparable<SemanticVersion> {
  private static final Pattern FORM =
      Pattern.compile(
          "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

  public SemanticVersion {
    prerelease = List.copyOf(prerelease);
  }

  public static SemanticVersion parse(String value) {
    var m = FORM.matcher(value == null ? "" : value);
    if (!m.matches()) throw new IllegalArgumentException("Invalid semantic version");
    var ids = m.group(4) == null ? List.<String>of() : List.of(m.group(4).split("\\.", -1));
    if (ids.stream().anyMatch(String::isEmpty) || ids.stream().anyMatch(id -> id.matches("0\\d+")))
      throw new IllegalArgumentException("Invalid semantic version prerelease");
    return new SemanticVersion(
        new BigInteger(m.group(1)), new BigInteger(m.group(2)), new BigInteger(m.group(3)), ids);
  }

  @Override
  public int compareTo(SemanticVersion o) {
    int c = major.compareTo(o.major);
    if (c != 0) return c;
    c = minor.compareTo(o.minor);
    if (c != 0) return c;
    c = patch.compareTo(o.patch);
    if (c != 0) return c;
    if (prerelease.isEmpty() != o.prerelease.isEmpty()) return prerelease.isEmpty() ? 1 : -1;
    for (int i = 0; i < Math.min(prerelease.size(), o.prerelease.size()); i++) {
      String a = prerelease.get(i), b = o.prerelease.get(i);
      boolean an = a.matches("0|[1-9]\\d*"), bn = b.matches("0|[1-9]\\d*");
      c =
          an && bn
              ? new BigInteger(a).compareTo(new BigInteger(b))
              : an != bn ? (an ? -1 : 1) : a.compareTo(b);
      if (c != 0) return c;
    }
    return Integer.compare(prerelease.size(), o.prerelease.size());
  }

  public boolean isPrerelease() {
    return !prerelease.isEmpty();
  }
}
