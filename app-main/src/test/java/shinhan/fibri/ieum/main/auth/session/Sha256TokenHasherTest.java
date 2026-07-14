package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Sha256TokenHasherTest {

	@Test
	void hashIsDeterministicAndDoesNotExposeRawToken() {
		Sha256TokenHasher hasher = new Sha256TokenHasher();

		String first = hasher.hash("refresh-token");
		String second = hasher.hash("refresh-token");

		assertThat(first).isEqualTo(second);
		assertThat(first).isNotEqualTo("refresh-token");
		assertThat(first).hasSize(64);
	}
}
