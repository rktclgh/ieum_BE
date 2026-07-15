package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StaticResponseHeaderFilterTest {

	private final StaticResponseHeaderFilter filter = new StaticResponseHeaderFilter();

	@Test
	void addsNoCacheAndOpenerPolicyToHtmlBeforeTheResponseCommits() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/questions/");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.TEXT_HTML_VALUE + ";charset=UTF-8");
			httpResponse.getWriter().write("<html></html>");
			httpResponse.flushBuffer();
		});

		assertThat(response.isCommitted()).isTrue();
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy"))
			.isEqualTo("same-origin-allow-popups");
	}

	@Test
	void addsNoCacheToRscText() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/questions/index.txt");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.TEXT_PLAIN_VALUE);
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isNull();
	}

	@Test
	void addsNoCacheToWebManifest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/manifest.webmanifest");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType("application/manifest+json");
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
	}

	@Test
	void addsNoCacheToRootServiceWorkerScript() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sw.js");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType("text/javascript");
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
		assertThat(response.getHeader("Service-Worker-Allowed")).isNull();
	}

	@Test
	void leavesNonRootJavaScriptCacheHeadersUntouched() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/sw.js");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType("text/javascript");
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
		assertThat(response.getHeader("Service-Worker-Allowed")).isNull();
	}

	@Test
	void addsLongImmutableCacheToNextStaticAsset() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/_next/static/chunks/app-2fd50f88.js"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType("text/javascript");
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
			.isEqualTo("public, max-age=31536000, immutable");
	}

	@Test
	void missingNextStaticAssetUsesHtmlErrorHeadersInsteadOfImmutableCache() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/_next/static/chunks/missing.js"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
			httpResponse.setContentType(MediaType.TEXT_HTML_VALUE);
			httpResponse.getWriter().write("<html>missing</html>");
			httpResponse.flushBuffer();
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy"))
			.isEqualTo("same-origin-allow-popups");
	}

	@Test
	void missingRscResponseStillReceivesHtmlOpenerPolicy() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missing/index.txt");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
			httpResponse.setContentType(MediaType.TEXT_HTML_VALUE);
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy"))
			.isEqualTo("same-origin-allow-popups");
	}

	@Test
	void addsShortCacheToIcon() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/icons/icon-192.png");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.IMAGE_PNG_VALUE);
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("public, max-age=3600");
	}

	@Test
	void leavesApiJsonHeadersUntouched() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
			httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isNull();
	}

	@Test
	void leavesApiSseHeadersUntouched() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications/subscribe");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
			httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-transform");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isNull();
	}

	@Test
	void leavesOpenApiYamlHtmlErrorHeadersUntouched() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs.yaml");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.TEXT_HTML_VALUE);
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
		assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isNull();
	}

	@Test
	void openApiYamlNearMissStillUsesFrontendHtmlHeaders() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs.yaml.evil");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
			httpResponse.setContentType(MediaType.TEXT_HTML_VALUE);
		});

		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
		assertThat(response.getHeader("Cross-Origin-Opener-Policy"))
			.isEqualTo("same-origin-allow-popups");
	}
}
