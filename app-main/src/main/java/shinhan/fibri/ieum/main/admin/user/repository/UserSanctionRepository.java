package shinhan.fibri.ieum.main.admin.user.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;

public interface UserSanctionRepository extends JpaRepository<UserSanction, Long> {

	boolean existsByUserIdAndReleasedAtIsNull(Long userId);

	Optional<UserSanction> findByUserIdAndReleasedAtIsNull(Long userId);

	List<UserSanction> findByUserIdOrderByCreatedAtDesc(Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select sanction from UserSanction sanction where sanction.id = :sanctionId")
	Optional<UserSanction> findByIdForUpdate(@Param("sanctionId") Long sanctionId);

	@Query("""
		select new shinhan.fibri.ieum.main.admin.user.repository.ExpiredSanctionRef(sanction.id, sanction.userId)
		from UserSanction sanction
		where sanction.releasedAt is null
		  and sanction.type = shinhan.fibri.ieum.main.admin.user.domain.SanctionType.temporary
		  and sanction.endsAt <= :now
		order by sanction.endsAt asc, sanction.id asc
		""")
	List<ExpiredSanctionRef> findExpiredTemporarySanctions(@Param("now") OffsetDateTime now);
}
