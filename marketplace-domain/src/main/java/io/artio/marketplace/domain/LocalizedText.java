package io.artio.marketplace.domain;

import java.util.Map;
import java.util.Set;

public record LocalizedText(Map<String, String> values) {
  public static final Set<String> LOCALES = Set.of("en", "de", "fr", "it");

  public LocalizedText {
    values = values == null ? Map.of() : Map.copyOf(values);
    if (!values.containsKey("en"))
      throw new IllegalArgumentException("English localization is required");
    if (!LOCALES.containsAll(values.keySet()))
      throw new IllegalArgumentException("Only en, de, fr and it locales are supported");
    if (values.values().stream().anyMatch(v -> v == null || v.isBlank()))
      throw new IllegalArgumentException("Localized values must not be blank");
  }

  public String resolve(String locale) {
    String key = locale == null ? "en" : locale.toLowerCase(java.util.Locale.ROOT);
    if (!LOCALES.contains(key)) throw new IllegalArgumentException("Unsupported locale");
    return values.getOrDefault(key, values.get("en"));
  }
}
