package shinhan.fibri.ieum.common.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.common.auth.domain.Country;

public interface CountryRepository extends JpaRepository<Country, String> {

	boolean existsByCodeAndIsActiveTrue(String code);
}
