package shinhan.fibri.ieum.main.report.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.main.ai.client.AiServiceClient;
import shinhan.fibri.ieum.main.file.storage.FileObjectMetadata;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.file.storage.StoredFileStream;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;
import shinhan.fibri.ieum.main.report.repository.JdbcReportAiWorkRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest(properties = "app.ai.report.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	JdbcReportAiWorkRepository.class,
	ReportAiPipelineIntegrationTest.TestBeans.class,
	ReportAiResultApplierImpl.class,
	ReportAiReviewResultMapper.class,
	ReportAiWorkProcessor.class,
	ReportReviewRequestFactory.class
})
class ReportAiPipelineIntegrationTest {

	private static final String DATABASE = "ieum_report_ai_pipeline";
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-14T12:00:00+09:00");

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private ReportAiWorkProcessor processor;

	@Autowired
	private ReportAiResultApplier resultApplier;

	@Autowired
	private FakeAiServiceClient aiServiceClient;

	private long reportId;
	private String contextSnapshot;
	private String contextHash;

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add(
			"spring.jpa.properties.hibernate.dialect",
			() -> "shinhan.fibri.ieum.common.config.SnakeCasePostgreSQLDialect"
		);
	}

	@BeforeEach
	void setUp() {
		aiServiceClient.reset();
		seedUsersAndRoom();
		reportId = insertDueReport();
		contextSnapshot = reportContextSnapshot(reportId);
		contextHash = sha256(contextSnapshot);
		jdbc.sql("UPDATE reports SET context_hash = :contextHash WHERE report_id = :reportId")
			.param("contextHash", contextHash)
			.param("reportId", reportId)
			.update();
	}

	@Test
	void completesPendingReportWithOneAiSanctionAndSuspendsUserWithoutDuplicatingSameAttempt() {
		assertThat(processor.processNext()).isTrue();
		entityManager.flush();
		entityManager.clear();

		assertThat(reportState()).isEqualTo("completed");
		assertThat(reportStatus()).isEqualTo("ai_reviewed");
		assertThat(aiSanctionCount()).isEqualTo(1);
		assertThat(userStatus(2L)).isEqualTo("suspended");

		ReportReviewRequest request = aiServiceClient.lastRequest();
		ClaimedReport duplicateAttempt = new ClaimedReport(
			reportId,
			100L,
			1L,
			2L,
			ReportReason.abuse,
			"reported abusive message",
			contextSnapshot,
			contextHash,
			request.reviewAttemptId(),
			1,
			NOW.plusMinutes(2)
		);

		assertThat(resultApplier.apply(duplicateAttempt, aiServiceClient.suspendHighResponse()))
			.isEqualTo(ReportAiApplyOutcome.stale("suspend"));
		entityManager.flush();
		entityManager.clear();

		assertThat(aiSanctionCount()).isEqualTo(1);
		assertThat(userStatus(2L)).isEqualTo("suspended");
	}

	private void seedUsersAndRoom() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, provider, password_hash, nickname, email_verified, role, status, created_at, updated_at)
			VALUES
			    (1, 'reporter@example.com', 'email', 'hash', 'reporter', true, 'user', 'active', :now, :now),
			    (2, 'reported@example.com', 'email', 'hash', 'reported', true, 'user', 'active', :now, :now)
			""").param("now", NOW).update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key, created_at)
			VALUES (10, 'direct', 'd:1:2', :now)
			""").param("now", NOW).update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content, created_at)
			VALUES (99, 10, 1, 'please stop', :before),
			       (100, 10, 2, 'reported abusive message', :reported),
			       (101, 10, 1, 'that was abusive', :after)
			""")
			.param("before", NOW.minusMinutes(2))
			.param("reported", NOW.minusMinutes(1))
			.param("after", NOW)
			.update();
	}

	private long insertDueReport() {
		return jdbc.sql("""
			INSERT INTO reports (
			    reporter_id, message_id, reported_user_id, reason, detail, context_snapshot, context_hash,
			    status, ai_review_state, ai_attempts, ai_next_attempt_at, created_at
			)
			VALUES (
			    1, 100, 2, 'abuse', 'reported abusive message', :snapshot::jsonb, :contextHash,
			    'pending', 'pending', 0, :dueAt, :createdAt
			)
			RETURNING report_id
			""")
			.param("snapshot", rawSnapshot())
			.param("contextHash", "0".repeat(64))
			.param("dueAt", NOW.minusMinutes(1))
			.param("createdAt", NOW)
			.query(Long.class)
			.single();
	}

	private String rawSnapshot() {
		return """
			{
			  "schemaVersion": 1,
			  "roomId": 10,
			  "before": [
			    {
			      "messageId": 99,
			      "senderId": 1,
			      "content": "please stop",
			      "createdAt": "2026-07-14T11:58:00+09:00"
			    }
			  ],
			  "reported": {
			    "messageId": 100,
			    "senderId": 2,
			    "content": "reported abusive message",
			    "createdAt": "2026-07-14T11:59:00+09:00"
			  },
			  "after": [
			    {
			      "messageId": 101,
			      "senderId": 1,
			      "content": "that was abusive",
			      "createdAt": "2026-07-14T12:00:00+09:00"
			    }
			  ]
			}
			""";
	}

	private String reportContextSnapshot(long reportId) {
		return jdbc.sql("SELECT context_snapshot::text FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId)
			.query(String.class)
			.single();
	}

	private String reportState() {
		return jdbc.sql("SELECT ai_review_state::text FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId)
			.query(String.class)
			.single();
	}

	private String reportStatus() {
		return jdbc.sql("SELECT status::text FROM reports WHERE report_id = :reportId")
			.param("reportId", reportId)
			.query(String.class)
			.single();
	}

	private int aiSanctionCount() {
		return jdbc.sql("""
			SELECT COUNT(*)
			FROM user_sanctions
			WHERE user_id = 2
			  AND report_id = :reportId
			  AND decision_source = 'ai_recommendation'
			""")
			.param("reportId", reportId)
			.query(Integer.class)
			.single();
	}

	private String userStatus(long userId) {
		return jdbc.sql("SELECT status::text FROM users WHERE user_id = :userId")
			.param("userId", userId)
			.query(String.class)
			.single();
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	@TestConfiguration
	static class TestBeans {

		@Bean
		JdbcClient jdbcClient(DataSource dataSource) {
			return JdbcClient.create(dataSource);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		Clock clock() {
			return Clock.fixed(NOW.toInstant(), ZoneOffset.ofHours(9));
		}

		@Bean
		ReportAiWorkerProperties reportAiWorkerProperties() {
			return new ReportAiWorkerProperties("integration-worker", Duration.ofMinutes(2), 5, 1);
		}

		@Bean
		ReportAiRetryPolicy reportAiRetryPolicy() {
			return new ReportAiRetryPolicy();
		}

		@Bean
		FakeAiServiceClient aiServiceClient(ObjectMapper objectMapper) {
			return new FakeAiServiceClient(objectMapper);
		}

		@Bean
		ReportAiPostCommitActions reportAiPostCommitActions() {
			return userId -> {
			};
		}

		@Bean
		FileStorage fileStorage() {
			return new FileStorage() {
				@Override
				public URI createPresignedPutUrl(String key, String contentType, Duration ttl) {
					throw new UnsupportedOperationException("not used by this test");
				}

				@Override
				public URI createPresignedGetUrl(String key, Duration ttl) {
					return URI.create("https://example.test/" + key);
				}

				@Override
				public FileObjectMetadata head(String key) {
					throw new UnsupportedOperationException("not used by this test");
				}

				@Override
				public StoredFileStream get(String key) {
					throw new UnsupportedOperationException("not used by this test");
				}

				@Override
				public void put(String key, String contentType, byte[] bytes) {
					throw new UnsupportedOperationException("not used by this test");
				}

				@Override
				public void delete(String key) {
					throw new UnsupportedOperationException("not used by this test");
				}
			};
		}
	}

	static class FakeAiServiceClient implements AiServiceClient {

		private final ObjectMapper objectMapper;
		private ReportReviewRequest lastRequest;

		FakeAiServiceClient(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		@Override
		public ReportReviewResponse review(ReportReviewRequest request) {
			this.lastRequest = request;
			return suspendHighResponse();
		}

		ReportReviewRequest lastRequest() {
			return lastRequest;
		}

		ReportReviewResponse suspendHighResponse() {
			var evidence = objectMapper.createArrayNode();
			evidence.addObject().put("messageId", 100L).put("type", "text");
			var matchedRules = objectMapper.createArrayNode();
			matchedRules.addObject().put("ruleCode", "ABUSE-HIGH-001").put("revision", 1);
			var policySnapshot = objectMapper.createObjectNode();
			policySnapshot.put("policySetHash", "a".repeat(64));
			policySnapshot.putArray("rules").addObject()
				.put("ruleCode", "ABUSE-HIGH-001")
				.put("category", "abuse")
				.put("decision", "suspend")
				.put("severity", "high")
				.put("minConfidence", 0.97)
				.put("revision", 1);
			var providerAttempts = objectMapper.createArrayNode();
			providerAttempts.addObject()
				.put("provider", "fake")
				.put("model", "fake-report-model-v1")
				.put("outcome", "success")
				.putNull("errorCode")
				.put("latencyMs", 1);
			return new ReportReviewResponse(
				"suspend",
				"abuse",
				"high",
				new BigDecimal("0.9800"),
				"high severity abusive content",
				evidence,
				matchedRules,
				"a".repeat(64),
				policySnapshot,
				"fake-report-model-v1",
				"report-review-v1",
				false,
				providerAttempts
			);
		}

		void reset() {
			this.lastRequest = null;
		}
	}
}
