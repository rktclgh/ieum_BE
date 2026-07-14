package shinhan.fibri.ieum.main.auth.service;

import java.util.concurrent.CompletableFuture;

public interface VerificationMailSender {

	CompletableFuture<Void> sendSignupCode(String email, String code, int expiresInSeconds);
}
