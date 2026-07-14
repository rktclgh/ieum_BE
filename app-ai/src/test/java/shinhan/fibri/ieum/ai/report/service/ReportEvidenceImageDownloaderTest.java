package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;

class ReportEvidenceImageDownloaderTest {

	private static final String ALLOWED_HOST = "ieum-files.s3.ap-northeast-2.amazonaws.com";
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(5);
	private ExecutorService bodyReadExecutor;

	@BeforeEach
	void setUp() {
		bodyReadExecutor = Executors.newVirtualThreadPerTaskExecutor();
	}

	@AfterEach
	void tearDown() {
		bodyReadExecutor.close();
	}

	@Test
	void downloadsAValidatedWebpImageWithABoundedGetRequest() {
		StubHttpClient client = new StubHttpClient(response(200, "image/webp", webpBytes()));
		ReportEvidenceImageDownloader downloader = downloader(client, 12L);

		VerifiedReportEvidenceImage image = downloader.download(image());

		assertThat(image.contentType()).isEqualTo("image/webp");
		assertThat(image.bytes()).containsExactly(webpBytes());
		assertThat(client.request.method()).isEqualTo("GET");
		assertThat(client.request.timeout()).contains(DOWNLOAD_TIMEOUT);
	}

	@Test
	void rejectsRedirectResponsesInsteadOfFollowingThem() {
		ReportEvidenceImageDownloader downloader = downloader(new StubHttpClient(response(302, "image/webp", webpBytes())), 12L);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("status");
	}

	@Test
	void closesTheResponseBodyWhenItRejectsTheStatusBeforeReading() {
		CloseTrackingInputStream body = new CloseTrackingInputStream(webpBytes());
		ReportEvidenceImageDownloader downloader = downloader(
			new StubHttpClient(response(302, "image/webp", webpBytes().length, body)),
			12L
		);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class);

		assertThat(body.closed).isTrue();
	}

	@Test
	void closesTheResponseBodyWhenItRejectsTheContentTypeBeforeReading() {
		CloseTrackingInputStream body = new CloseTrackingInputStream(webpBytes());
		ReportEvidenceImageDownloader downloader = downloader(
			new StubHttpClient(response(200, "image/jpeg", webpBytes().length, body)),
			12L
		);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class);

		assertThat(body.closed).isTrue();
	}

	@Test
	@Timeout(1)
	void stopsAndClosesAStalledResponseBodyWithinTheDownloadTimeout() {
		BlockingInputStream body = new BlockingInputStream();
		ReportEvidenceImageDownloader downloader = downloader(
			new StubHttpClient(responseWithoutContentLength(200, "image/webp", body)),
			12L,
			Duration.ofMillis(50)
		);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("timed out");

		assertThat(body.closed).isTrue();
	}

	@Test
	void rejectsAnHttpClientConfiguredToFollowRedirects() {
		assertThatThrownBy(() -> new ReportEvidenceImageDownloader(
			new StubHttpClient(response(200, "image/webp", webpBytes()), HttpClient.Redirect.NORMAL),
			new ReportEvidenceImageUrlValidator(Set.of(ALLOWED_HOST)),
			12L,
			DOWNLOAD_TIMEOUT,
			bodyReadExecutor
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redirect");
	}

	@Test
	void rejectsResponsesWhoseDeclaredLengthExceedsThePerImageLimit() {
		ReportEvidenceImageDownloader downloader = downloader(
			new StubHttpClient(response(200, "image/webp", 13L, webpBytes())),
			12L
		);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("size");
	}

	@Test
	void rejectsAResponseThatExceedsTheLimitWithoutAContentLengthHeader() {
		ReportEvidenceImageDownloader downloader = downloader(
			new StubHttpClient(responseWithoutContentLength(200, "image/webp", oversizedWebpBytes())),
			12L
		);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("size");
	}

	@Test
	void rejectsAResponseWithANonWebpContentType() {
		ReportEvidenceImageDownloader downloader = downloader(new StubHttpClient(response(200, "image/jpeg", webpBytes())), 12L);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("Content-Type");
	}

	@Test
	void rejectsAResponseWhoseBytesAreNotWebp() {
		ReportEvidenceImageDownloader downloader = downloader(
			new StubHttpClient(response(200, "image/webp", new byte[] {1, 2, 3, 4})),
			12L
		);

		assertThatThrownBy(() -> downloader.download(image()))
			.isInstanceOf(ReportEvidenceImageDownloadException.class)
			.hasMessageContaining("WebP");
	}

	private ReportEvidenceImageDownloader downloader(HttpClient client, long maxBytes) {
		return downloader(client, maxBytes, DOWNLOAD_TIMEOUT);
	}

	private ReportEvidenceImageDownloader downloader(HttpClient client, long maxBytes, Duration timeout) {
		return new ReportEvidenceImageDownloader(
			client,
			new ReportEvidenceImageUrlValidator(Set.of(ALLOWED_HOST)),
			maxBytes,
			timeout,
			bodyReadExecutor
		);
	}

	private ReportReviewImage image() {
		return new ReportReviewImage(
			"image/webp",
			"https://" + ALLOWED_HOST + "/final/42/chat/id/display.webp?X-Amz-Signature=secret"
		);
	}

	private static byte[] webpBytes() {
		return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
	}

	private static byte[] oversizedWebpBytes() {
		return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
	}

	private static HttpResponse<InputStream> response(int status, String contentType, byte[] body) {
		return response(status, contentType, (long) body.length, body);
	}

	private static HttpResponse<InputStream> response(int status, String contentType, long contentLength, byte[] body) {
		return response(status, contentType, contentLength, new ByteArrayInputStream(body));
	}

	private static HttpResponse<InputStream> response(int status, String contentType, long contentLength, InputStream body) {
		return new StubHttpResponse(
			status,
			HttpHeaders.of(Map.of(
				"Content-Type", List.of(contentType),
				"Content-Length", List.of(Long.toString(contentLength))
			), (name, value) -> true),
			body
		);
	}

	private static HttpResponse<InputStream> responseWithoutContentLength(int status, String contentType, byte[] body) {
		return responseWithoutContentLength(status, contentType, new ByteArrayInputStream(body));
	}

	private static HttpResponse<InputStream> responseWithoutContentLength(int status, String contentType, InputStream body) {
		return new StubHttpResponse(
			status,
			HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true),
			body
		);
	}

	private static final class StubHttpClient extends HttpClient {

		private final HttpResponse<InputStream> response;
		private final Redirect redirect;
		private HttpRequest request;

		private StubHttpClient(HttpResponse<InputStream> response) {
			this(response, Redirect.NEVER);
		}

		private StubHttpClient(HttpResponse<InputStream> response, Redirect redirect) {
			this.response = response;
			this.redirect = redirect;
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.empty();
		}

		@Override
		public Redirect followRedirects() {
			return redirect;
		}

		@Override
		public Optional<ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			return null;
		}

		@Override
		public SSLParameters sslParameters() {
			return new SSLParameters();
		}

		@Override
		public Optional<Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_2;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
			throws IOException, InterruptedException {
			this.request = request;
			return (HttpResponse<T>) response;
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
			HttpRequest request,
			HttpResponse.BodyHandler<T> responseBodyHandler,
			HttpResponse.PushPromiseHandler<T> pushPromiseHandler
		) {
			throw new UnsupportedOperationException();
		}
	}

	private record StubHttpResponse(int statusCode, HttpHeaders headers, InputStream body) implements HttpResponse<InputStream> {

		@Override
		public HttpRequest request() {
			return null;
		}

		@Override
		public Optional<HttpResponse<InputStream>> previousResponse() {
			return Optional.empty();
		}

		@Override
		public Optional<SSLSession> sslSession() {
			return Optional.empty();
		}

		@Override
		public URI uri() {
			return URI.create("https://" + ALLOWED_HOST + "/display.webp");
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_2;
		}
	}

	private static final class CloseTrackingInputStream extends ByteArrayInputStream {

		private boolean closed;

		private CloseTrackingInputStream(byte[] bytes) {
			super(bytes);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	private static final class BlockingInputStream extends InputStream {

		private boolean closed;

		@Override
		public int read() throws IOException {
			awaitClose();
			return -1;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			awaitClose();
			return -1;
		}

		@Override
		public synchronized void close() {
			closed = true;
			notifyAll();
		}

		private synchronized void awaitClose() throws IOException {
			while (!closed) {
				try {
					wait();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IOException("interrupted", exception);
				}
			}
		}
	}
}
