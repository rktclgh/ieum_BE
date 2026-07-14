package shinhan.fibri.ieum.main.auth.exception;

public class RefreshTokenReusedException extends RuntimeException {

	public RefreshTokenReusedException() {
		super("Refresh token was reused");
	}
}
