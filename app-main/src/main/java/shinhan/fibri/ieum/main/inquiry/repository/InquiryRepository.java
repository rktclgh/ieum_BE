package shinhan.fibri.ieum.main.inquiry.repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

	List<Inquiry> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Inquiry i where i.id = :id")
	Optional<Inquiry> findByIdForUpdate(@Param("id") Long id);
}
