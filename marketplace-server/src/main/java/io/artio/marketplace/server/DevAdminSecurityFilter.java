package io.artio.marketplace.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Restricts the no-login development console to a direct loopback browser session. */
@Component
@Profile("marketplace-local-dev")
public final class DevAdminSecurityFilter extends OncePerRequestFilter {
  public static final String CSRF_ATTRIBUTE = DevAdminSecurityFilter.class.getName() + ".csrf";
  private static final Set<String> FORWARDED =
      Set.of(
          "Forwarded",
          "X-Forwarded-For",
          "X-Forwarded-Host",
          "X-Forwarded-Proto",
          "X-Real-IP",
          "X-Original-Host");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/dev-admin/")
        && !path.equals("/dev-admin")
        && !path.startsWith("/api/v1/dev-admin/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Frame-Options", "DENY");
    if (request.getRequestURI().startsWith("/api/v1/dev-admin/"))
      response.setHeader("Cache-Control", "no-store");
    if (!isAllowedPeer(request.getRemoteAddr())
        || !validHost(request.getHeader("Host"))
        || FORWARDED.stream().anyMatch(name -> request.getHeader(name) != null)) {
      response.sendError(404);
      return;
    }
    if (!request.getRequestURI().startsWith("/api/v1/dev-admin/")) {
      chain.doFilter(request, response);
      return;
    }
    if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
      String origin = request.getHeader("Origin");
      String host = request.getHeader("Host");
      if (origin == null || host == null || !origin.equals("http://" + host)) {
        response.sendError(403, "Same-origin request required");
        return;
      }
      HttpSession session = request.getSession(false);
      String expected = session == null ? null : (String) session.getAttribute(CSRF_ATTRIBUTE);
      String actual = request.getHeader("X-Artio-CSRF");
      if (expected == null
          || actual == null
          || !java.security.MessageDigest.isEqual(
              expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
              actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
        response.sendError(403, "Development session CSRF check failed");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  static String createCsrfToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean validHost(String host) {
    if (host == null || host.length() > 128) return false;
    String value = host.toLowerCase(java.util.Locale.ROOT);
    if (value.equals("localhost") || value.equals("127.0.0.1") || value.equals("[::1]"))
      return true;
    var match =
        java.util.regex.Pattern.compile("^(localhost|127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})$")
            .matcher(value);
    if (!match.matches()) return false;
    try {
      int port = Integer.parseInt(match.group(2));
      return port > 0 && port <= 65535;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static boolean isLoopback(String address) {
    try {
      return java.net.InetAddress.getByName(address).isLoopbackAddress();
    } catch (Exception ignored) {
      return false;
    }
  }

  private static boolean isAllowedPeer(String address) {
    // Docker's host-loopback port forward arrives at the container from its Compose
    // network gateway. The host port itself is bound to 127.0.0.1; accept only that
    // exact gateway peer in addition to a direct loopback socket.
    return isLoopback(address) || address.equals(composeGatewayAddress());
  }

  private static String composeGatewayAddress() {
    try (var lines = java.nio.file.Files.lines(java.nio.file.Path.of("/proc/net/route"))) {
      return lines
          .skip(1)
          .map(line -> line.trim().split("\\s+"))
          .filter(fields -> fields.length > 2 && "00000000".equals(fields[1]))
          .map(
              fields -> {
                long value = Long.parseLong(fields[2], 16);
                return ((value) & 255)
                    + "."
                    + ((value >> 8) & 255)
                    + "."
                    + ((value >> 16) & 255)
                    + "."
                    + ((value >> 24) & 255);
              })
          .findFirst()
          .orElse("");
    } catch (Exception ignored) {
      return "";
    }
  }
}
