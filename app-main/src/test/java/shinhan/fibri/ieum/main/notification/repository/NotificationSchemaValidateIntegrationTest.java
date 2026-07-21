package shinhan.fibri.ieum.main.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

/**
 * 알림 i18n 컬럼이 <b>정본 스키마</b>(`db/schema.sql`)와 맞는지 검증한다.
 *
 * <p>다른 알림 통합 테스트는 인라인 DDL + {@code ddl-auto=none}이라 엔티티 매핑과 실제 스키마가
 * 어긋나도 통과한다. 이 테스트만 {@code ddl-auto=validate}로 정본 스키마 위에 컨텍스트를 띄우므로,
 * {@code message_params}의 {@code @JdbcTypeCode(SqlTypes.JSON)} ↔ {@code jsonb} 매핑이 틀리면
 * 여기서만 잡힌다. 운영은 {@code validate}로 뜨기 때문에 이 검증이 곧 기동 가능 여부다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// ★ 스캔을 Notification 으로 좁힌 이유: validate 는 매핑된 전 엔티티를 검사하는데,
//   이 레포에는 이미 정본 스키마와 어긋난 엔티티가 있다(answer_images.sort_order —
//   엔티티 int/INTEGER vs 스키마 SMALLINT/int2). 그게 먼저 터져 검증이 중단되면
//   정작 확인하려던 notifications 매핑까지 못 본다. 범위를 좁혀 이 변경분만 검사한다.
//   (그 불일치는 이 작업 범위 밖의 선행 이슈다.)
@EntityScan(basePackageClasses = Notification.class)
// 엔티티를 좁히면 리포지터리 스캔도 같이 좁혀야 한다. 안 그러면 스캔에서 빠진 엔티티를 참조하는
// 다른 리포지터리(meetingScheduleRepository 등)가 "Not a managed type" 으로 컨텍스트를 깬다.
@EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
class NotificationSchemaValidateIntegrationTest {

	private static final String DATABASE = "ieum_main_notification_validate";

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		// ★ 이 테스트의 존재 이유. none 으로 바꾸면 검증력이 사라진다.
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}

	@Autowired
	private NotificationRepository notificationRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
	}

	@Test
	void bootsWithValidateAgainstCanonicalSchema() {
		// 이 메서드가 실행됐다는 사실 자체가 검증 결과다. 매핑이 스키마와 어긋나면 Hibernate 가
		// SchemaManagementException 으로 컨텍스트 로드를 실패시켜 여기 도달하지 못한다.
		assertThat(notificationRepository).isNotNull();
	}

	@Test
	void roundTripsMessageParamsThroughJsonbColumn() {
		long userId = insertUser("i18n-user");

		Notification saved = notificationRepository.saveAndFlush(Notification.of(
			userId,
			NotificationType.friend,
			NotificationMessage.of(NotificationMessageKey.FRIEND_REQUEST, Map.of("nickname", "철수")),
			"친구 요청",
			"철수님이 친구 요청을 보냈어요",
			userId,
			null
		));
		entityManager.clear();

		Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getMessageKey()).isEqualTo("notification.friend.request");
		assertThat(reloaded.getMessageParams()).containsExactly(Map.entry("nickname", "철수"));
		// 이중 기록 — 키를 못 읽는 구클라이언트를 위해 ko 폴백도 함께 남아야 한다.
		assertThat(reloaded.getTitle()).isEqualTo("친구 요청");
		assertThat(reloaded.getBody()).isEqualTo("철수님이 친구 요청을 보냈어요");

		// 문자열이 아니라 실제 jsonb 로 저장됐는지 DB 연산자로 확인한다.
		assertThat(jdbc.queryForObject(
			"SELECT message_params ->> 'nickname' FROM notifications WHERE notification_id = ?",
			String.class,
			saved.getId()
		)).isEqualTo("철수");
	}

	@Test
	void storesSqlNullWhenMessageHasNoParams() {
		long userId = insertUser("no-params");

		Notification saved = notificationRepository.saveAndFlush(Notification.of(
			userId,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED),
			"답변 채택",
			"회원님의 답변이 채택됐어요",
			1L,
			false
		));
		entityManager.clear();

		assertThat(jdbc.queryForObject(
			"SELECT message_params IS NULL FROM notifications WHERE notification_id = ?",
			Boolean.class,
			saved.getId()
		)).isTrue();
		assertThat(notificationRepository.findById(saved.getId()).orElseThrow().getMessageParams()).isEmpty();
	}

	@Test
	void keepsLegacyRowsReadableWithoutMessageKey() {
		long userId = insertUser("legacy");
		// v37 이전에 쌓인 행의 모양 — message_key/message_params 가 NULL 이고 title/body 만 있다.
		Long legacyId = jdbc.queryForObject("""
			INSERT INTO notifications (user_id, type, title, body, ref_id)
			VALUES (?, 'question'::notification_type, '새 답변', '회원님의 질문에 답변이 달렸어요', 1)
			RETURNING notification_id
			""", Long.class, userId);

		Notification legacy = notificationRepository.findById(legacyId).orElseThrow();
		assertThat(legacy.getMessageKey()).isNull();
		assertThat(legacy.getMessageParams()).isEmpty();
		assertThat(legacy.getTitle()).isEqualTo("새 답변");
	}

	private long insertUser(String nickname) {
		return jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (?, 'hash', ?, true)
			RETURNING user_id
			""", Long.class, nickname + "@example.com", nickname);
	}
}
