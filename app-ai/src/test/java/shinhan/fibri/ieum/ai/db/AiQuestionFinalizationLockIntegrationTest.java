package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class AiQuestionFinalizationLockIntegrationTest {

	private static final String CANONICAL_DATABASE = "ieum_ai_question_finalization_lock";
	private static final String MIGRATION_DATABASE = "ieum_ai_question_finalization_lock_v16";
	private static final AtomicLong SEQUENCE = new AtomicLong();

	private DataSource dataSource;
	private JdbcClient jdbc;

	@BeforeEach
	void setUpCanonicalSchema() {
		CanonicalPostgresContainer.recreateDatabase(CANONICAL_DATABASE);
		SqlScriptRunner.run(CANONICAL_DATABASE, "schema.sql");
		dataSource = CanonicalPostgresContainer.dataSource(CANONICAL_DATABASE);
		jdbc = JdbcClient.create(dataSource);
	}

	@Test
	void activeQuestionLockReturnsTrueOnlyWhenQuestionAndPinAreActive() {
		QuestionFixture active = insertQuestion(jdbc, false, false);
		QuestionFixture deletedQuestion = insertQuestion(jdbc, true, false);
		QuestionFixture deletedPin = insertQuestion(jdbc, false, true);

		assertThat(lockActiveQuestion(jdbc, active.questionId())).isTrue();
		assertThat(lockActiveQuestion(jdbc, deletedQuestion.questionId())).isFalse();
		assertThat(lockActiveQuestion(jdbc, deletedPin.questionId())).isFalse();
	}

	@Test
	void answerFunctionHoldsShareLocksOnQuestionAndPinUntilItsTransactionEnds() throws Exception {
		QuestionFixture fixture = insertQuestion(jdbc, false, false);

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			callInsertAnswer(holder, fixture.questionId(), "AI answer");

			assertSoftDeleteIsBlocked(
				dataSource,
				"UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = ?",
				fixture.questionId()
			);
			assertSoftDeleteIsBlocked(
				dataSource,
				"UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = ?",
				fixture.pinId()
			);

			holder.commit();
		}

		assertThat(jdbc.sql("UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = :questionId")
			.param("questionId", fixture.questionId())
			.update()).isOne();
		assertThat(jdbc.sql("UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = :pinId")
			.param("pinId", fixture.pinId())
			.update()).isOne();
	}

	@Test
	void answerFunctionIsIdempotentForAnActiveQuestion() {
		QuestionFixture fixture = insertQuestion(jdbc, false, false);

		long firstAnswerId = insertAnswer(jdbc, fixture.questionId(), "first answer");
		long secondAnswerId = insertAnswer(jdbc, fixture.questionId(), "second answer");

		assertThat(secondAnswerId).isEqualTo(firstAnswerId);
		assertThat(jdbc.sql("SELECT count(*) FROM answers WHERE question_id = :questionId AND is_ai")
			.param("questionId", fixture.questionId())
			.query(Integer.class)
			.single()).isOne();
		assertThat(jdbc.sql("SELECT content FROM answers WHERE answer_id = :answerId")
			.param("answerId", firstAnswerId)
			.query(String.class)
			.single()).isEqualTo("first answer");
	}

	@Test
	void concurrentAnswerFunctionCallsConvergeToOneAnswer() throws Exception {
		QuestionFixture fixture = insertQuestion(jdbc, false, false);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Callable<Long> firstCall = concurrentAnswerCall(fixture.questionId(), "first", ready, start);
			Callable<Long> secondCall = concurrentAnswerCall(fixture.questionId(), "second", ready, start);
			Future<Long> first = executor.submit(firstCall);
			Future<Long> second = executor.submit(secondCall);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertThat(List.of(
				first.get(10, TimeUnit.SECONDS),
				second.get(10, TimeUnit.SECONDS)
			)).containsOnly(first.get());
		}

		assertThat(jdbc.sql("SELECT count(*) FROM answers WHERE question_id = :questionId AND is_ai")
			.param("questionId", fixture.questionId())
			.query(Integer.class)
			.single()).isOne();
	}

	@Test
	void canonicalFunctionsUseHardenedSecurityDefinerContracts() {
		assertFunctionContract(jdbc, "public.ai_lock_active_question(bigint)", false);
		assertFunctionContract(jdbc, "public.insert_ai_answer_if_active(bigint,text)", true);
	}

	@Test
	void v16MigrationHardensTheExistingV15Functions() throws Exception {
		CanonicalPostgresContainer.recreateDatabase(MIGRATION_DATABASE);
		SqlScriptRunner.run(
			MIGRATION_DATABASE,
			"test-baselines/schema-v12.sql",
			"migrations/v13_app_ai_v2_expand.sql",
			"migrations/v14_report_worklist_expand.sql",
			"migrations/v15_question_ai_ticket_notification.sql",
			"migrations/v16_question_ai_finalization_lock.sql"
		);
		DataSource migratedDataSource = CanonicalPostgresContainer.dataSource(MIGRATION_DATABASE);
		JdbcClient migratedJdbc = JdbcClient.create(migratedDataSource);

		assertFunctionContract(migratedJdbc, "public.ai_lock_active_question(bigint)", false);
		assertFunctionContract(migratedJdbc, "public.insert_ai_answer_if_active(bigint,text)", true);
		QuestionFixture fixture = insertQuestion(migratedJdbc, false, false);
		assertThat(lockActiveQuestion(migratedJdbc, fixture.questionId())).isTrue();

		try (Connection holder = migratedDataSource.getConnection()) {
			holder.setAutoCommit(false);
			callInsertAnswer(holder, fixture.questionId(), "migrated AI answer");
			assertSoftDeleteIsBlocked(
				migratedDataSource,
				"UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = ?",
				fixture.pinId()
			);
			holder.rollback();
		}
	}

	private boolean lockActiveQuestion(JdbcClient client, long questionId) {
		return client.sql("SELECT public.ai_lock_active_question(:questionId)")
			.param("questionId", questionId)
			.query(Boolean.class)
			.single();
	}

	private long insertAnswer(JdbcClient client, long questionId, String content) {
		return client.sql("SELECT public.insert_ai_answer_if_active(:questionId, :content)")
			.param("questionId", questionId)
			.param("content", content)
			.query(Long.class)
			.single();
	}

	private Callable<Long> concurrentAnswerCall(
		long questionId,
		String content,
		CountDownLatch ready,
		CountDownLatch start
	) {
		return () -> {
			ready.countDown();
			assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
			return insertAnswer(JdbcClient.create(dataSource), questionId, content);
		};
	}

	private void callInsertAnswer(Connection connection, long questionId, String content) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT public.insert_ai_answer_if_active(?, ?)"
		)) {
			statement.setLong(1, questionId);
			statement.setString(2, content);
			statement.executeQuery();
		}
	}

	private void assertSoftDeleteIsBlocked(DataSource targetDataSource, String sql, long id) throws SQLException {
		SQLException failure;
		try (Connection contender = targetDataSource.getConnection()) {
			contender.setAutoCommit(false);
			try (PreparedStatement timeout = contender.prepareStatement("SET LOCAL lock_timeout = '300ms'")) {
				timeout.execute();
			}
			try (PreparedStatement softDelete = contender.prepareStatement(sql)) {
				softDelete.setLong(1, id);
				try {
					softDelete.executeUpdate();
					failure = null;
				} catch (SQLException exception) {
					failure = exception;
				}
			} finally {
				contender.rollback();
			}
		}

		assertThat((Throwable) failure).isNotNull();
		assertThat(failure.getSQLState()).isEqualTo("55P03");
	}

	private void assertFunctionContract(JdbcClient client, String signature, boolean insertsAnswers) {
		Optional<FunctionContract> contract = client.sql("""
			SELECT routine.prosecdef,
			       routine.proconfig @> ARRAY['search_path=pg_catalog'] AS safe_search_path,
			       NOT EXISTS (
			           SELECT 1
			             FROM aclexplode(COALESCE(routine.proacl, acldefault('f', routine.proowner))) privilege
			            WHERE privilege.grantee = 0
			              AND privilege.privilege_type = 'EXECUTE'
			       ) AS public_execute_revoked,
			       pg_get_functiondef(routine.oid) AS definition
			  FROM pg_proc routine
			 WHERE routine.oid = to_regprocedure(:signature)
			""")
			.param("signature", signature)
			.query((resultSet, rowNumber) -> new FunctionContract(
				resultSet.getBoolean("prosecdef"),
				resultSet.getBoolean("safe_search_path"),
				resultSet.getBoolean("public_execute_revoked"),
				resultSet.getString("definition")
			))
			.optional();

		assertThat(contract).isPresent();
		assertThat(contract.orElseThrow().securityDefiner()).isTrue();
		assertThat(contract.orElseThrow().safeSearchPath()).isTrue();
		assertThat(contract.orElseThrow().publicExecuteRevoked()).isTrue();
		assertThat(contract.orElseThrow().definition())
			.contains("public.questions", "public.pins", "FOR SHARE OF q, p");
		if (insertsAnswers) {
			assertThat(contract.orElseThrow().definition()).contains("public.answers");
		}
	}

	private QuestionFixture insertQuestion(JdbcClient client, boolean deletedQuestion, boolean deletedPin) {
		long sequence = SEQUENCE.incrementAndGet();
		long userId = client.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, TRUE)
			RETURNING user_id
			""")
			.param("email", "question-lock-" + sequence + "@example.com")
			.param("nickname", "question-lock-" + sequence)
			.query(Long.class)
			.single();
		long pinId = client.sql("""
			INSERT INTO pins (author_id, pin_type, location, address, deleted_at)
			VALUES (
			    :userId,
			    'question',
			    ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
			    'Seoul',
			    CASE WHEN :deletedPin THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.param("deletedPin", deletedPin)
			.query(Long.class)
			.single();
		long questionId = client.sql("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (
			    :pinId,
			    :userId,
			    'question title',
			    'question content',
			    CASE WHEN :deletedQuestion THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.param("deletedQuestion", deletedQuestion)
			.query(Long.class)
			.single();
		return new QuestionFixture(questionId, pinId);
	}

	private record QuestionFixture(long questionId, long pinId) {
	}

	private record FunctionContract(
		boolean securityDefiner,
		boolean safeSearchPath,
		boolean publicExecuteRevoked,
		String definition
	) {
	}
}
