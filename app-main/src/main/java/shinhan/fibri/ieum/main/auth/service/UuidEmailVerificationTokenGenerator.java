package shinhan.fibri.ieum.main.auth.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidEmailVerificationTokenGenerator implements EmailVerificationTokenGenerator {

	@Override
	public String generate() {
		return UUID.randomUUID().toString();
	}
}
