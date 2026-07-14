package shinhan.fibri.ieum.main.auth.service;

import java.time.Duration;
import java.util.Optional;

public interface SocialSignupTokenStore {

	void save(String token, SocialSignupIdentity identity, Duration ttl);

	Optional<SocialSignupIdentity> find(String token);

	void delete(String token);
}
