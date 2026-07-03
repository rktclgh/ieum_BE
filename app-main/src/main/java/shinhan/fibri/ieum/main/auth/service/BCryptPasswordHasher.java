package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BCryptPasswordHasher implements PasswordHasher {

	private final PasswordEncoder encoder;

	@Override
	public String hash(String rawPassword) {
		return encoder.encode(rawPassword);
	}
}
