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
	@Query("""
		select sanction
		from UserSanction sanction
		where sanction.userId = :userId
		  and sanction.revokedAt is null
		order by sanction.createdAt asc, sanction.id asc
		""")
	List<UserSanction> findByUserIdAndRevokedAtIsNullForUpdate(@Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select sanction
		from UserSanction sanction
		where sanction.userId = :userId
		  and sanction.revokedAt is null
		  and sanction.reviewStatus <> shinhan.fibri.ieum.main.admin.user.domain.SanctionReviewStatus.dismissed
		  and (
		      sanction.type = shinhan.fibri.ieum.main.admin.user.domain.SanctionType.permanent
		      or sanction.endsAt > :now
		  )
		order by sanction.endsAt desc nulls first, sanction.id asc
		""")
	List<UserSanction> findEffectiveSanctionsForUpdate(
		@Param("userId") Long userId,
		@Param("now") OffsetDateTime now
	);

	@Query("""
		select max(sanction.endsAt)
		from UserSanction sanction
		where sanction.userId = :userId
		  and sanction.revokedAt is null
		  and sanction.reviewStatus <> shinhan.fibri.ieum.main.admin.user.domain.SanctionReviewStatus.dismissed
		  and sanction.type = shinhan.fibri.ieum.main.admin.user.domain.SanctionType.temporary
		  and sanction.endsAt > :now
		""")
	Optional<OffsetDateTime> findMaxEffectiveTemporaryEndsAt(
		@Param("userId") Long userId,
		@Param("now") OffsetDateTime now
	);

	@Query("""
		select count(sanction) > 0
		from UserSanction sanction
		where sanction.userId = :userId
		  and sanction.revokedAt is null
		  and sanction.reviewStatus <> shinhan.fibri.ieum.main.admin.user.domain.SanctionReviewStatus.dismissed
		  and (
		      sanction.type = shinhan.fibri.ieum.main.admin.user.domain.SanctionType.permanent
		      or sanction.endsAt > :now
		  )
		""")
	boolean existsEffectiveSanction(
		@Param("userId") Long userId,
		@Param("now") OffsetDateTime now
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select sanction from UserSanction sanction where sanction.id = :sanctionId")
	Optional<UserSanction> findByIdForUpdate(@Param("sanctionId") Long sanctionId);

	@Query("""
		select new shinhan.fibri.ieum.main.admin.user.repository.ExpiredSanctionRef(sanction.id, sanction.userId)
		from UserSanction sanction
		where sanction.revokedAt is null
		  and sanction.reviewStatus <> shinhan.fibri.ieum.main.admin.user.domain.SanctionReviewStatus.dismissed
		  and sanction.type = shinhan.fibri.ieum.main.admin.user.domain.SanctionType.temporary
		  and sanction.endsAt <= :now
		  and exists (
		      select targetUser.id
		      from User targetUser
		      where targetUser.id = sanction.userId
		        and targetUser.status = shinhan.fibri.ieum.common.auth.domain.UserStatus.suspended
		  )
		order by sanction.endsAt asc, sanction.id asc
		""")
	List<ExpiredSanctionRef> findExpiredTemporarySanctions(@Param("now") OffsetDateTime now);
}
