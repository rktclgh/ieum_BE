package shinhan.fibri.ieum.common.auth.repository;

import java.util.Optional;
import java.util.UUID;
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
