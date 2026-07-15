package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import shinhan.fibri.ieum.main.admin.user.scheduler.SanctionExpiryScheduler;
import shinhan.fibri.ieum.main.meeting.scheduler.MeetingRecurrenceExpansionScheduler;
import shinhan.fibri.ieum.main.meeting.scheduler.MeetingScheduleCompletionScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticFrontendHttpIntegrationTest {

	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	@LocalServerPort
	private int port;

	@MockitoBean
	private SanctionExpiryScheduler sanctionExpiryScheduler;

	@MockitoBean
	private MeetingRecurrenceExpansionScheduler recurrenceExpansionScheduler;

	@MockitoBean
	private MeetingScheduleCompletionScheduler scheduleCompletionScheduler;

	@Test
	void servesForwardedHtmlWithoutConsultingAnInvalidAuthCookie() throws Exception {
		HttpResponse<String> response = get("/login", "access_token=invalid-static-cookie");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("STATIC_LOGIN_PAGE");
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
			value -> assertThat(value).startsWith("text/html")
		);
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache");
		assertThat(response.headers().firstValue("Cross-Origin-Opener-Policy"))
			.contains("same-origin-allow-popups");
	}

	@Test
	void appliesSeparateCachePoliciesToRscAndHashedAssets() throws Exception {
		HttpResponse<String> rsc = get("/login/index.txt", null);
		HttpResponse<String> hashedAsset = get("/_next/static/chunks/test-hash.js", null);

		assertThat(rsc.statusCode()).isEqualTo(200);
		assertThat(rsc.body()).contains("STATIC_LOGIN_RSC");
		assertThat(rsc.headers().firstValue("Cache-Control")).contains("no-cache");
		assertThat(hashedAsset.statusCode()).isEqualTo(200);
		assertThat(hashedAsset.headers().firstValue("Cache-Control"))
			.contains("public, max-age=31536000, immutable");
	}

	@Test
	void returnsNextHtml404ForFrontendAndJsonUnauthorizedForApi() throws Exception {
		HttpResponse<String> frontend = get("/missing/frontend/path", null);
		HttpResponse<String> api = get("/api/missing/path", null);

		assertThat(frontend.statusCode()).isEqualTo(404);
		assertThat(frontend.body()).contains("NEXT_STATIC_404");
		assertThat(frontend.headers().firstValue("Content-Type")).hasValueSatisfying(
			value -> assertThat(value).startsWith("text/html")
		);
		assertThat(api.statusCode()).isEqualTo(401);
		assertThat(api.headers().firstValue("Content-Type")).hasValueSatisfying(
			value -> assertThat(value).startsWith("application/json")
		);
		assertThat(api.body()).contains("AUTHENTICATION_REQUIRED");
	}

	@Test
	void redirectsLegacyNumericRouteWithoutFollowingIt() throws Exception {
		HttpResponse<String> response = get("/meetups/5", null);

		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(response.headers().firstValue("Location"))
			.contains("/meetups/detail/?meetingId=5");
	}

	private HttpResponse<String> get(String path, String cookie) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:" + port + path))
			.GET();
		if (cookie != null) {
			request.header("Cookie", cookie);
		}
		return httpClient.send(
			request.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
	}
}
