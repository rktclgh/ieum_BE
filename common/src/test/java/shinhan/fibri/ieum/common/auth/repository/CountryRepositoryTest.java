package shinhan.fibri.ieum.common.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import shinhan.fibri.ieum.common.auth.domain.Country;
import shinhan.fibri.ieum.common.auth.domain.User;

@DataJpaTest
class CountryRepositoryTest {

	@Autowired
	private CountryRepository countryRepository;

	@Test
	void existsByCodeAndIsActiveTrueOnlyMatchesActiveCountry() {
		countryRepository.save(Country.active("KR", "대한민국", "South Korea"));
		countryRepository.save(Country.inactive("ZZ", "비활성", "Inactive"));

		assertThat(countryRepository.existsByCodeAndIsActiveTrue("KR")).isTrue();
		assertThat(countryRepository.existsByCodeAndIsActiveTrue("ZZ")).isFalse();
		assertThat(countryRepository.existsByCodeAndIsActiveTrue("US")).isFalse();
	}

	@SpringBootApplication(scanBasePackages = "shinhan.fibri.ieum.common")
	@EntityScan(basePackageClasses = {User.class, Country.class})
	static class TestApplication {
	}
}
