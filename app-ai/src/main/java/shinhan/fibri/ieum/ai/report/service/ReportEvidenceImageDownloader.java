package shinhan.fibri.ieum.ai.report.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;

public class ReportEvidenceImageDownloader implements ReportEvidenceImageFetcher {

	private static final String WEBP_CONTENT_TYPE = "image/webp";
	private static final int BUFFER_SIZE = 8 * 1024;

	private final HttpClient httpClient;
	private final ReportEvidenceImageUrlValidator urlValidator;
	private final long maxBytes;
	private final Duration downloadTimeout;
	private final ExecutorService bodyReadExecutor;

	public ReportEvidenceImageDownloader(
		HttpClient httpClient,
		ReportEvidenceImageUrlValidator urlValidator,
		long maxBytes,
		Duration downloadTimeout,
		ExecutorService bodyReadExecutor
	) {
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
		this.urlValidator = Objects.requireNonNull(urlValidator, "urlValidator must not be null");
		if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
			throw new IllegalArgumentException("httpClient must not follow redirects");
		}
		if (maxBytes < 12 || maxBytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("maxBytes must be between 12 and " + Integer.MAX_VALUE);
		}
		if (downloadTimeout == null || downloadTimeout.isZero() || downloadTimeout.isNegative()) {
			throw new IllegalArgumentException("downloadTimeout must be positive");
		}
		this.maxBytes = maxBytes;
		this.downloadTimeout = downloadTimeout;
		this.bodyReadExecutor = Objects.requireNonNull(bodyReadExecutor, "bodyReadExecutor must not be null");
	}

	public VerifiedReportEvidenceImage download(ReportReviewImage image) {
		return download(image, maxBytes);
	}

	@Override
	public VerifiedReportEvidenceImage download(ReportReviewImage image, long maxAllowedBytes) {
		if (maxAllowedBytes < 12 || maxAllowedBytes > maxBytes) {
			throw new IllegalArgumentException("maxAllowedBytes must be between 12 and " + maxBytes);
		}
		if (image == null || image.contentType() == null || !WEBP_CONTENT_TYPE.equalsIgnoreCase(image.contentType().trim())) {
			throw new InvalidReportReviewRequestException("image contentType must be image/webp");
		}
		URI uri = urlValidator.validate(image.presignedGetUrl());
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(downloadTimeout)
			.GET()
			.build();

		long deadlineNanos = System.nanoTime() + downloadTimeout.toNanos();
		HttpResponse<InputStream> response = send(request);
		InputStream body = response.body();
		if (body == null) {
			throw failed("image download response body is missing");
		}
		try (body) {
			if (response.statusCode() != 200) {
				throw failed("image download returned an unexpected status");
			}
			if (response.headers().firstValueAsLong("Content-Length").orElse(0L) > maxAllowedBytes) {
				throw failed("image download exceeds the size limit");
			}
			if (!isWebpContentType(response.headers().firstValue("Content-Type").orElse(null))) {
				throw failed("image download returned an invalid Content-Type");
			}

			byte[] bytes = readBoundedWithinDeadline(body, deadlineNanos, maxAllowedBytes);
			if (!isWebp(bytes)) {
				throw failed("image download did not contain WebP bytes");
			}
			return new VerifiedReportEvidenceImage(WEBP_CONTENT_TYPE, bytes);
		} catch (IOException exception) {
			throw failed("image download failed", exception);
		}
	}

	private HttpResponse<InputStream> send(HttpRequest request) {
		try {
			return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw failed("image download was interrupted", exception);
		} catch (IOException exception) {
			throw failed("image download failed", exception);
		}
	}

	private byte[] readBoundedWithinDeadline(InputStream body, long deadlineNanos, long maxAllowedBytes) {
		long remainingNanos = deadlineNanos - System.nanoTime();
		if (remainingNanos <= 0) {
			throw failed("image download timed out");
		}

		Future<byte[]> future = bodyReadExecutor.submit(() -> readBounded(body, maxAllowedBytes));
		try {
			return future.get(remainingNanos, TimeUnit.NANOSECONDS);
		} catch (TimeoutException exception) {
			closeQuietly(body);
			future.cancel(true);
			throw failed("image download timed out", exception);
		} catch (InterruptedException exception) {
			closeQuietly(body);
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw failed("image download was interrupted", exception);
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof ReportEvidenceImageDownloadException downloadException) {
				throw downloadException;
			}
			if (cause instanceof IOException ioException) {
				throw failed("image download failed", ioException);
			}
			throw failed("image download failed", cause);
		}
	}

	private byte[] readBounded(InputStream body, long maxAllowedBytes) throws IOException {
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[BUFFER_SIZE];
			long total = 0;
			int read;
			while ((read = body.read(buffer)) != -1) {
				total += read;
				if (total > maxAllowedBytes) {
					throw failed("image download exceeds the size limit");
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private void closeQuietly(InputStream body) {
		try {
			body.close();
		} catch (IOException ignored) {
			// The pending download already has a terminal failure.
		}
	}

	private boolean isWebpContentType(String contentType) {
		if (contentType == null) {
			return false;
		}
		return WEBP_CONTENT_TYPE.equals(contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
	}

	private boolean isWebp(byte[] bytes) {
		return bytes.length >= 12
			&& bytes[0] == 'R'
			&& bytes[1] == 'I'
			&& bytes[2] == 'F'
			&& bytes[3] == 'F'
			&& bytes[8] == 'W'
			&& bytes[9] == 'E'
			&& bytes[10] == 'B'
			&& bytes[11] == 'P';
	}

	private ReportEvidenceImageDownloadException failed(String message) {
		return new ReportEvidenceImageDownloadException(message);
	}

	private ReportEvidenceImageDownloadException failed(String message, Throwable cause) {
		return new ReportEvidenceImageDownloadException(message, cause);
	}
}
