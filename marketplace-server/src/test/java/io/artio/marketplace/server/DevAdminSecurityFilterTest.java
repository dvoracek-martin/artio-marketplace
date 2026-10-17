package io.artio.marketplace.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class DevAdminSecurityFilterTest {
  private final DevAdminSecurityFilter filter = new DevAdminSecurityFilter();

  @Test
  void rejectsForwardedClientIdentityEvenWhenTheSocketIsLoopback() throws Exception {
    var request = request("GET");
    request.addHeader("X-Forwarded-For", "127.0.0.1");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals(404, response.getStatus());
    assertTrue(chain.getRequest() == null);
  }

  @Test
  void mutationRequiresSameOriginAndSessionBoundCsrf() throws Exception {
    var request = request("POST");
    request.addHeader("Origin", "http://localhost:8080");
    var session = new MockHttpSession();
    session.setAttribute(DevAdminSecurityFilter.CSRF_ATTRIBUTE, "known-token");
    request.setSession(session);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertEquals(403, response.getStatus());

    request = request("POST");
    request.addHeader("Origin", "http://localhost:8080");
    request.addHeader("X-Artio-CSRF", "known-token");
    request.setSession(session);
    response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals(200, response.getStatus());
    assertTrue(chain.getRequest() != null);
  }

  @Test
  void rejectsNonLoopbackHostAndPeer() throws Exception {
    var request = request("GET");
    request.setRemoteAddr("192.0.2.5");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertEquals(404, response.getStatus());
  }

  private MockHttpServletRequest request(String method) {
    var request = new MockHttpServletRequest(method, "/api/v1/dev-admin/plugins");
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("Host", "localhost:8080");
    return request;
  }
}
