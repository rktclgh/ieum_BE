package shinhan.fibri.ieum.common.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CountryTest {

	@Test
	void activeCountryUsesSeoulCreatedAt() {
		Country country = Country.active("KR", "대한민국", "South Korea");

		assertThat(country.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
	}
}
