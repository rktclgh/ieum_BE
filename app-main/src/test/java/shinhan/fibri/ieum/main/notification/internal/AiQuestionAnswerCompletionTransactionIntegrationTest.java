package shinhan.fibri.ieum.main.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest
class AiQuestionAnswerCompletionTransactionIntegrationTest {

	private static final long QUESTION_ID = 10L;
	private static final long PIN_ID = 20L;
	private static final long ANSWER_ID = 40L;

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "ai_completion_transaction");
	}

	@Autowired
	private AiQuestionAnswerCompletionService service;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private AiQuestionAnswerCompletionRepository repository;

	private long recipientUserId;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM notifications").update();
		String suffix = UUID.randomUUID().toString();
		recipientUserId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "callback-rollback-" + suffix + "@example.com")
			.param("nickname", "rollback-" + suffix.substring(0, 8))
			.query(Long.class)
			.single();
	}

	@Test
	void rollsBackTheInsertedNotificationWhenAcknowledgementCannotBeWritten() {
		when(repository.lockTicket(QUESTION_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedTicket("completed", ANSWER_ID, null)
		));
		when(repository.lockQuestion(QUESTION_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedQuestion(PIN_ID, recipientUserId, false)
		));
		when(repository.lockPin(PIN_ID)).thenReturn(Optional.of(
			new AiQuestionAnswerCompletionRepository.LockedPin(false)
		));
		when(repository.isMatchingAiAnswer(QUESTION_ID, ANSWER_ID)).thenReturn(true);
		when(repository.acknowledgeNotification(QUESTION_ID, ANSWER_ID)).thenReturn(0);

		assertThatThrownBy(() -> service.complete(QUESTION_ID, ANSWER_ID))
			.isInstanceOf(AiQuestionAnswerCompletionConflictException.class);

		assertThat(jdbc.sql("""
			SELECT count(*)
			  FROM notifications
			 WHERE user_id = :userId
			   AND event_key = :eventKey
			""")
			.param("userId", recipientUserId)
			.param("eventKey", "answer-created:" + ANSWER_ID)
			.query(Long.class)
			.single()).isZero();
	}
}
