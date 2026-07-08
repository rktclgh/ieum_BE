package shinhan.fibri.ieum.common.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmailAndProviderAndDeletedAtIsNull(String email, AuthProvider provider);

	boolean existsByNicknameAndDeletedAtIsNull(String nickname);

	boolean existsByProfileFileIdAndDeletedAtIsNull(UUID profileFileId);

	Optional<User> findByEmailAndProviderAndDeletedAtIsNull(String email, AuthProvider provider);

	Optional<User> findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider provider, String providerUid);

	Optional<User> findByIdAndDeletedAtIsNull(Long userId);

	@Query("""
		SELECT u
		FROM User u
		WHERE LOWER(u.nickname) LIKE LOWER(CONCAT('%', :nickname, '%'))
		  AND u.deletedAt IS NULL
		ORDER BY u.nickname ASC, u.id ASC
		""")
	List<User> searchActiveUsersByNickname(@Param("nickname") String nickname, Pageable pageable);

	@Modifying
	@Query(
		value = """
			UPDATE users
			SET last_location = ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326),
			    updated_at = now()
			WHERE user_id = :userId
			  AND deleted_at IS NULL
			""",
		nativeQuery = true
	)
	int updateLastLocation(
		@Param("userId") Long userId,
		@Param("longitude") double longitude,
		@Param("latitude") double latitude
	);
}
