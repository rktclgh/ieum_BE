package shinhan.fibri.ieum.ai.report.service;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ReportEvidenceImageUrlValidator {

	private final Set<String> allowedHosts;

	public ReportEvidenceImageUrlValidator(Set<String> allowedHosts) {
		if (allowedHosts == null || allowedHosts.isEmpty()) {
			throw new IllegalArgumentException("allowedHosts must not be empty");
		}
		this.allowedHosts = allowedHosts.stream()
			.filter(Objects::nonNull)
			.map(String::trim)
			.filter(host -> !host.isEmpty())
			.map(host -> host.toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
		if (this.allowedHosts.isEmpty()) {
			throw new IllegalArgumentException("allowedHosts must not be empty");
		}
	}

	public URI validate(String presignedGetUrl) {
		if (presignedGetUrl == null || presignedGetUrl.isBlank()) {
			throw invalid("image presignedGetUrl is required");
		}

		URI uri;
		try {
			uri = URI.create(presignedGetUrl);
		} catch (IllegalArgumentException exception) {
			throw invalid("image presignedGetUrl must be a valid URL");
		}

		String host = uri.getHost();
		if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
			throw invalid("image URL must use HTTPS");
		}
		if (host == null || host.isBlank() || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null) {
			throw invalid("image URL must be a host URL without user info, port, or fragment");
		}

		String normalizedHost = host.toLowerCase(Locale.ROOT);
		if (isIpLiteral(normalizedHost)) {
			throw invalid("image URL host must not be an IP literal");
		}
		if (!allowedHosts.contains(normalizedHost)) {
			throw invalid("image URL host is not allowed");
		}
		return uri;
	}

	private boolean isIpLiteral(String host) {
		return host.contains(":") || host.matches("[0-9.]+") || host.matches("0[xX][0-9a-fA-F]+");
	}

	private InvalidReportReviewRequestException invalid(String message) {
		return new InvalidReportReviewRequestException(message);
	}
}
