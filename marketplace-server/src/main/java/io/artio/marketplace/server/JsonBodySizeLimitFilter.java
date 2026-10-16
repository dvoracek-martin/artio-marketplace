package io.artio.marketplace.server;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON request bytes even when a client omits Content-Length and streams the body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
final class JsonBodySizeLimitFilter extends OncePerRequestFilter {
  static final int MAX_JSON_BYTES = 524_288;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, jakarta.servlet.FilterChain chain)
      throws jakarta.servlet.ServletException, IOException {
    if (request.getContentType() == null
        || !request
            .getContentType()
            .toLowerCase(java.util.Locale.ROOT)
            .startsWith("application/json")) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > MAX_JSON_BYTES) {
      reject(response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
    if (body.length > MAX_JSON_BYTES || request.getInputStream().read() != -1) {
      reject(response);
      return;
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setContentType("application/json");
    response
        .getOutputStream()
        .write(
            "{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"JSON body exceeds 512 KiB.\"}"
                .getBytes(StandardCharsets.UTF_8));
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new IllegalStateException("Asynchronous request reads are not supported");
        }
      };
    }
  }
}
