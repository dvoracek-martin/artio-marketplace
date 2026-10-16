package io.artio.marketplace.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JsonBodySizeLimitFilterTest {
  @Test
  void rejectsOversizedJsonWithOrWithoutContentLength() throws Exception {
    var filter = new JsonBodySizeLimitFilter();
    for (boolean unknownLength : new boolean[] {false, true}) {
      var request = new MockHttpServletRequest("POST", "/api/v1/admin/marketplace/releases");
      request.setContentType("application/json");
      request.setContent(new byte[JsonBodySizeLimitFilter.MAX_JSON_BYTES + 1]);
      jakarta.servlet.http.HttpServletRequest input = request;
      if (unknownLength)
        input =
            new jakarta.servlet.http.HttpServletRequestWrapper(request) {
              @Override
              public int getContentLength() {
                return -1;
              }

              @Override
              public long getContentLengthLong() {
                return -1;
              }
            };
      var response = new MockHttpServletResponse();
      var calls = new AtomicInteger();
      filter.doFilter(input, response, (req, res) -> calls.incrementAndGet());
      assertThat(response.getStatus()).isEqualTo(413);
      assertThat(calls).hasValue(0);
    }
  }

  @Test
  void forwardsBoundedJsonBodyUnchanged() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/admin/marketplace/releases");
    request.setContentType("application/json");
    request.setContent("{\"value\":\"é\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    var seen = new java.util.concurrent.atomic.AtomicReference<String>();
    new JsonBodySizeLimitFilter()
        .doFilter(
            request,
            response,
            (req, res) ->
                seen.set(
                    new String(
                        req.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(seen.get()).isEqualTo("{\"value\":\"é\"}");
  }
}
