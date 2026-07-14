package shinhan.fibri.ieum.ai.question.webgrounding;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class PublicWebSourceUriValidator {

	private static final int MAX_URI_LENGTH = 2048;

	Optional<URI> validate(String rawUri) {
		if (rawUri == null) {
			return Optional.empty();
		}
		String normalized = rawUri.trim();
		if (normalized.isEmpty() || normalized.length() > MAX_URI_LENGTH) {
			return Optional.empty();
		}

		try {
			URI uri = new URI(normalized);
			if (!uri.isAbsolute() || !isHttp(uri.getScheme()) || uri.getUserInfo() != null) {
				return Optional.empty();
			}
			int port = uri.getPort();
			if (port != -1 && port != 80 && port != 443) {
				return Optional.empty();
			}
			String host = normalizeHost(uri.getHost());
			if (host == null || isLocalHostname(host) || !isPublicHost(host)) {
				return Optional.empty();
			}
			return Optional.of(uri);
		}
		catch (URISyntaxException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	private boolean isHttp(String scheme) {
		return scheme != null
			&& ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
	}

	private String normalizeHost(String rawHost) {
		if (rawHost == null || rawHost.isBlank()) {
			return null;
		}
		String host = rawHost.trim().toLowerCase(Locale.ROOT);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		return host.isBlank() ? null : host;
	}

	private boolean isLocalHostname(String host) {
		return "localhost".equals(host)
			|| host.endsWith(".localhost")
			|| "local".equals(host)
			|| host.endsWith(".local")
			|| "internal".equals(host)
			|| host.endsWith(".internal");
	}

	private boolean isPublicHost(String host) {
		if (host.indexOf(':') >= 0) {
			return parseIpv6(host).filter(this::isPublicIpv6).isPresent();
		}
		if (host.matches("[0-9.]+")) {
			return parseIpv4(host).filter(this::isPublicIpv4).isPresent();
		}
		return host.indexOf('.') >= 0 && !looksLikeAlternativeIpv4(host);
	}

	private boolean looksLikeAlternativeIpv4(String host) {
		String[] labels = host.split("\\.", -1);
		if (labels.length == 0) {
			return false;
		}
		for (String label : labels) {
			if (!label.matches("(?i)(0x[0-9a-f]+|[0-9]+)")) {
				return false;
			}
		}
		return true;
	}

	private Optional<int[]> parseIpv4(String host) {
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4) {
			return Optional.empty();
		}
		int[] address = new int[4];
		for (int index = 0; index < parts.length; index++) {
			String part = parts[index];
			if (part.isEmpty() || (part.length() > 1 && part.startsWith("0"))) {
				return Optional.empty();
			}
			try {
				int value = Integer.parseInt(part);
				if (value < 0 || value > 255) {
					return Optional.empty();
				}
				address[index] = value;
			}
			catch (NumberFormatException exception) {
				return Optional.empty();
			}
		}
		return Optional.of(address);
	}

	private boolean isPublicIpv4(int[] address) {
		int first = address[0];
		int second = address[1];
		int third = address[2];
		return first != 0
			&& first != 10
			&& first != 127
			&& !(first == 100 && second >= 64 && second <= 127)
			&& !(first == 169 && second == 254)
			&& !(first == 172 && second >= 16 && second <= 31)
			&& !(first == 192 && second == 0 && third == 0)
			&& !(first == 192 && second == 0 && third == 2)
			&& !(first == 192 && second == 88 && third == 99)
			&& !(first == 192 && second == 168)
			&& !(first == 198 && (second == 18 || second == 19))
			&& !(first == 198 && second == 51 && third == 100)
			&& !(first == 203 && second == 0 && third == 113)
			&& first < 224;
	}

	private Optional<byte[]> parseIpv6(String host) {
		if (host.isEmpty() || host.indexOf('%') >= 0 || host.indexOf(':') < 0) {
			return Optional.empty();
		}
		int compression = host.indexOf("::");
		if (compression != host.lastIndexOf("::")) {
			return Optional.empty();
		}

		List<Integer> groups = new ArrayList<>(8);
		if (compression >= 0) {
			String left = host.substring(0, compression);
			String right = host.substring(compression + 2);
			List<Integer> leftGroups = parseIpv6Section(left, false);
			List<Integer> rightGroups = parseIpv6Section(right, true);
			if (leftGroups == null || rightGroups == null) {
				return Optional.empty();
			}
			int missing = 8 - leftGroups.size() - rightGroups.size();
			if (missing < 1) {
				return Optional.empty();
			}
			groups.addAll(leftGroups);
			for (int index = 0; index < missing; index++) {
				groups.add(0);
			}
			groups.addAll(rightGroups);
		}
		else {
			List<Integer> full = parseIpv6Section(host, true);
			if (full == null || full.size() != 8) {
				return Optional.empty();
			}
			groups.addAll(full);
		}

		byte[] address = new byte[16];
		for (int index = 0; index < groups.size(); index++) {
			int group = groups.get(index);
			address[index * 2] = (byte)(group >>> 8);
			address[index * 2 + 1] = (byte)group;
		}
		return Optional.of(address);
	}

	private List<Integer> parseIpv6Section(String section, boolean ipv4MayBeLast) {
		List<Integer> groups = new ArrayList<>();
		if (section.isEmpty()) {
			return groups;
		}
		String[] tokens = section.split(":", -1);
		for (int index = 0; index < tokens.length; index++) {
			String token = tokens[index];
			if (token.isEmpty()) {
				return null;
			}
			if (token.indexOf('.') >= 0) {
				if (!ipv4MayBeLast || index != tokens.length - 1) {
					return null;
				}
				Optional<int[]> ipv4 = parseIpv4(token);
				if (ipv4.isEmpty()) {
					return null;
				}
				int[] value = ipv4.orElseThrow();
				groups.add((value[0] << 8) | value[1]);
				groups.add((value[2] << 8) | value[3]);
				continue;
			}
			if (token.length() > 4) {
				return null;
			}
			try {
				groups.add(Integer.parseInt(token, 16));
			}
			catch (NumberFormatException exception) {
				return null;
			}
		}
		return groups;
	}

	private boolean isPublicIpv6(byte[] address) {
		int first = address[0] & 0xff;
		int second = address[1] & 0xff;
		if ((first & 0xe0) != 0x20) {
			return false;
		}
		if (first == 0x20 && second == 0x01) {
			int third = address[2] & 0xff;
			int fourth = address[3] & 0xff;
			if ((third & 0xfe) == 0 || (third == 0x0d && fourth == 0xb8)) {
				return false;
			}
		}
		if (first == 0x20 && second == 0x02) {
			return false;
		}
		return !(first == 0x3f
			&& second == 0xff
			&& ((address[2] & 0xff) & 0xf0) == 0);
	}
}
