package shinhan.fibri.ieum.main.notification.push;

import com.interaso.webpush.VapidKeys;
import java.math.BigInteger;
import java.util.Base64;

public final class WebPushTestKeys {

	private WebPushTestKeys() {
	}

	public static RawVapidKeys generateVapidKeys() {
		VapidKeys generated = VapidKeys.generate();
		return new RawVapidKeys(
			base64Url(generated.getApplicationServerKey()),
			base64Url(toFixedUnsigned(generated.getPrivateKey().getS(), 32))
		);
	}

	public static String generateSubscriptionP256dh() {
		return base64Url(VapidKeys.generate().getApplicationServerKey());
	}

	public static String authSecret() {
		return base64Url(new byte[16]);
	}

	private static byte[] toFixedUnsigned(BigInteger value, int size) {
		byte[] signed = value.toByteArray();
		byte[] result = new byte[size];
		int sourceStart = Math.max(0, signed.length - size);
		int length = Math.min(signed.length, size);
		System.arraycopy(signed, sourceStart, result, size - length, length);
		return result;
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public record RawVapidKeys(String publicKey, String privateKey) {
	}
}
