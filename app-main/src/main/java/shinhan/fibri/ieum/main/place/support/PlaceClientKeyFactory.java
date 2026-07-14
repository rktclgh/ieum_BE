package shinhan.fibri.ieum.main.place.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

@Component
public class PlaceClientKeyFactory {

	public String clientKey(AuthenticatedUser principal, String remoteAddress) {
		if (principal != null) {
			return principal.userId().toString();
		}
		return anonymousClientKey(remoteAddress);
	}

	public String anonymousClientKey(String remoteAddress) {
		return sha256("ip:" + (remoteAddress == null ? "" : remoteAddress));
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}
}
