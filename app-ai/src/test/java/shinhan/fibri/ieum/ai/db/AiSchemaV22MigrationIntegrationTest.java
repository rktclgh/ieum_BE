package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class AiSchemaV22MigrationIntegrationTest {

	private static final String CANONICAL_DATABASE = "ieum_ai_v22_schema";
	private static final String MIGRATION_DATABASE = "ieum_ai_v22_migration";
	private static final String FUNCTION_SIGNATURE = "public.ai_lock_eligible_accepted_answer(bigint)";
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

	@AfterAll
	static void cleanUpDatabases() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + CANONICAL_DATABASE + " WITH (FORCE)").update();
		admin.sql("DROP DATABASE IF EXISTS " + MIGRATION_DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void canonicalSchemaDefinesHardenedFunctionAndAcceptedHumanEligibility() {
		assertFunctionContract(jdbc);

		AnswerFixture acceptedHuman = insertAnswer(jdbc, true, false, false, false, "question");
		AnswerFixture ai = insertAnswer(jdbc, true, true, false, false, "question");
		AnswerFixture unaccepted = insertAnswer(jdbc, false, false, false, false, "question");
		AnswerFixture deletedQuestion = insertAnswer(jdbc, true, false, true, false, "question");
		AnswerFixture deletedPin = insertAnswer(jdbc, true, false, false, true, "question");
		AnswerFixture wrongPinType = insertAnswer(jdbc, true, false, false, false, "meeting");

		assertThat(lockEligibleAnswer(jdbc, acceptedHuman.answerId())).isTrue();
		assertThat(lockEligibleAnswer(jdbc, ai.answerId())).isFalse();
		assertThat(lockEligibleAnswer(jdbc, unaccepted.answerId())).isFalse();
		assertThat(lockEligibleAnswer(jdbc, deletedQuestion.answerId())).isFalse();
		assertThat(lockEligibleAnswer(jdbc, deletedPin.answerId())).isFalse();
		assertThat(lockEligibleAnswer(jdbc, wrongPinType.answerId())).isFalse();
		assertThat(lockEligibleAnswer(jdbc, Long.MAX_VALUE)).isFalse();
	}

	@Test
	void v22MigrationPreservesV21RowsMatchesCanonicalAndIsOneShot() {
		prepareV21Database();
		JdbcClient migrated = JdbcClient.create(CanonicalPostgresContainer.dataSource(MIGRATION_DATABASE));
		AnswerFixture fixture = insertAnswer(migrated, true, false, false, false, "question");
		RowCounts before = rowCounts(migrated);

		SqlScriptRunner.run(MIGRATION_DATABASE, "migrations/v22_accepted_answer_eligibility_lock.sql");

		assertFunctionContract(migrated);
		assertThat(lockEligibleAnswer(migrated, fixture.answerId())).isTrue();
		assertThat(rowCounts(migrated)).isEqualTo(before);
		assertThat(functionDefinition(migrated)).isEqualTo(functionDefinition(jdbc));

		assertThatThrownBy(() -> SqlScriptRunner.run(
			MIGRATION_DATABASE,
			"migrations/v22_accepted_answer_eligibility_lock.sql"
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ai_lock_eligible_accepted_answer")
			.hasMessageContaining("already exists");
		assertThat(rowCounts(migrated)).isEqualTo(before);
		assertThat(lockEligibleAnswer(migrated, fixture.answerId())).isTrue();
	}

	@Test
	void eligibilityLockSerializesAcceptedStateUpdates() throws Exception {
		AnswerFixture fixture = insertAnswer(jdbc, true, false, false, false, "question");

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			assertThat(callEligibilityLock(holder, fixture.answerId())).isTrue();

			assertUpdatesBlocked(List.of(new SqlUpdate(
				"UPDATE answers SET is_accepted = FALSE WHERE answer_id = ?",
				fixture.answerId()
			)));
			holder.commit();
		}

		assertThat(jdbc.sql("UPDATE answers SET is_accepted = FALSE WHERE answer_id = :answerId")
			.param("answerId", fixture.answerId())
			.update()).isOne();
	}

	@Test
	void eligibilityLockSerializesQuestionAndPinDeletion() throws Exception {
		AnswerFixture fixture = insertAnswer(jdbc, true, false, false, false, "question");

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			assertThat(callEligibilityLock(holder, fixture.answerId())).isTrue();

			assertUpdatesBlocked(List.of(
				new SqlUpdate(
					"UPDATE questions SET deleted_at = CURRENT_TIMESTAMP WHERE question_id = ?",
					fixture.questionId()
				),
				new SqlUpdate(
					"UPDATE pins SET deleted_at = CURRENT_TIMESTAMP WHERE pin_id = ?",
					fixture.pinId()
				)
			));
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
	void eligibilityRechecksThatTheLockedAnswerStillBelongsToTheLockedQuestion() throws Exception {
		AnswerFixture candidate = insertAnswer(jdbc, true, false, false, false, "question");
		AnswerFixture destination = insertAnswer(jdbc, false, false, false, false, "question");
		CountDownLatch started = new CountDownLatch(1);

		try (
			Connection mover = dataSource.getConnection();
			ExecutorService executor = Executors.newSingleThreadExecutor()
		) {
			mover.setAutoCommit(false);
			updateAnswerQuestion(mover, candidate.answerId(), destination.questionId());

			Future<Boolean> result = executor.submit(() -> callEligibilityLock(dataSource, candidate.answerId(), started));
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> result.get(300, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			mover.commit();
			assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
		}

		assertThat(jdbc.sql("SELECT question_id FROM answers WHERE answer_id = :answerId")
			.param("answerId", candidate.answerId())
			.query(Long.class)
			.single()).isEqualTo(destination.questionId());
	}

	private static void assertFunctionContract(JdbcClient client) {
		Optional<FunctionContract> contract = client.sql("""
			SELECT routine.prosecdef,
			       language.lanname,
			       routine.proconfig @> ARRAY['search_path=pg_catalog'] AS safe_search_path,
			       NOT EXISTS (
			           SELECT 1
			             FROM pg_catalog.aclexplode(
			                 COALESCE(routine.proacl, pg_catalog.acldefault('f', routine.proowner))
			             ) privilege
			            WHERE privilege.grantee = 0
			              AND privilege.privilege_type = 'EXECUTE'
			       ) AS public_execute_revoked,
			       pg_catalog.pg_get_function_result(routine.oid) AS result_type,
			       pg_catalog.pg_get_functiondef(routine.oid) AS definition
			  FROM pg_catalog.pg_proc routine
			  JOIN pg_catalog.pg_language language ON language.oid = routine.prolang
			 WHERE routine.oid = pg_catalog.to_regprocedure(:signature)
			""")
			.param("signature", FUNCTION_SIGNATURE)
			.query((resultSet, rowNumber) -> new FunctionContract(
				resultSet.getBoolean("prosecdef"),
				resultSet.getString("lanname"),
				resultSet.getBoolean("safe_search_path"),
				resultSet.getBoolean("public_execute_revoked"),
				resultSet.getString("result_type"),
				resultSet.getString("definition")
			))
			.optional();

		assertThat(contract).isPresent();
		FunctionContract function = contract.orElseThrow();
		assertThat(function.securityDefiner()).isTrue();
		assertThat(function.language()).isEqualTo("plpgsql");
		assertThat(function.safeSearchPath()).isTrue();
		assertThat(function.publicExecuteRevoked()).isTrue();
		assertThat(function.resultType()).isEqualTo("boolean");
		assertThat(function.definition()).containsSubsequence(
			"FROM public.questions q",
			"FOR SHARE OF q;",
			"FROM public.pins p",
			"FOR SHARE OF p;",
			"FROM public.answers a",
			"FOR SHARE OF a;"
		);
	}

	private static boolean lockEligibleAnswer(JdbcClient client, long answerId) {
		return client.sql("SELECT public.ai_lock_eligible_accepted_answer(:answerId)")
			.param("answerId", answerId)
			.query(Boolean.class)
			.single();
	}

	private static void prepareV21Database() {
		CanonicalPostgresContainer.recreateDatabase(MIGRATION_DATABASE);
		SqlScriptRunner.run(
			MIGRATION_DATABASE,
			"test-baselines/schema-v12.sql",
			"migrations/v13_app_ai_v2_expand.sql",
			"migrations/v14_report_worklist_expand.sql",
			"migrations/v15_question_ai_ticket_notification.sql",
			"migrations/v16_question_ai_finalization_lock.sql",
			"migrations/v17_question_ai_checkpoints.sql",
			"migrations/v18_knowledge_source_content_hash.sql",
			"migrations/v19_knowledge_import_lifecycle.sql",
			"migrations/v20_answer_report_target.sql",
			"migrations/v21_report_target_review_followup.sql"
		);
	}

	private static RowCounts rowCounts(JdbcClient client) {
		return client.sql("""
			SELECT (SELECT count(*) FROM users) AS users,
			       (SELECT count(*) FROM pins) AS pins,
			       (SELECT count(*) FROM questions) AS questions,
			       (SELECT count(*) FROM answers) AS answers
			""")
			.query((resultSet, rowNumber) -> new RowCounts(
				resultSet.getLong("users"),
				resultSet.getLong("pins"),
				resultSet.getLong("questions"),
				resultSet.getLong("answers")
			))
			.single();
	}

	private static String functionDefinition(JdbcClient client) {
		return client.sql("SELECT pg_catalog.pg_get_functiondef(pg_catalog.to_regprocedure(:signature))")
			.param("signature", FUNCTION_SIGNATURE)
			.query(String.class)
			.single();
	}

	private static boolean callEligibilityLock(Connection connection, long answerId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT public.ai_lock_eligible_accepted_answer(?)"
		)) {
			statement.setLong(1, answerId);
			try (var resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getBoolean(1);
			}
		}
	}

	private static boolean callEligibilityLock(
		DataSource targetDataSource,
		long answerId,
		CountDownLatch started
	) throws SQLException {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement timeout = connection.prepareStatement("SET LOCAL lock_timeout = '3s'")) {
				timeout.execute();
			}
			started.countDown();
			boolean eligible = callEligibilityLock(connection, answerId);
			connection.commit();
			return eligible;
		}
	}

	private void assertUpdatesBlocked(List<SqlUpdate> updates) throws Exception {
		CountDownLatch started = new CountDownLatch(updates.size());
		try (ExecutorService executor = Executors.newFixedThreadPool(updates.size())) {
			List<Future<String>> results = updates.stream()
				.map(update -> executor.submit(() -> executeWithLockTimeout(update, started)))
				.toList();
			assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
			for (Future<String> result : results) {
				assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("55P03");
			}
		}
	}

	private String executeWithLockTimeout(SqlUpdate update, CountDownLatch started) throws SQLException {
		try (Connection contender = dataSource.getConnection()) {
			contender.setAutoCommit(false);
			try (PreparedStatement timeout = contender.prepareStatement("SET LOCAL lock_timeout = '500ms'")) {
				timeout.execute();
			}
			started.countDown();
			try (PreparedStatement statement = contender.prepareStatement(update.sql())) {
				statement.setLong(1, update.id());
				statement.executeUpdate();
				return null;
			}
			catch (SQLException exception) {
				return exception.getSQLState();
			}
			finally {
				contender.rollback();
			}
		}
	}

	private static void updateAnswerQuestion(Connection connection, long answerId, long questionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"UPDATE answers SET question_id = ? WHERE answer_id = ?"
		)) {
			statement.setLong(1, questionId);
			statement.setLong(2, answerId);
			assertThat(statement.executeUpdate()).isOne();
		}
	}

	private static AnswerFixture insertAnswer(
		JdbcClient client,
		boolean accepted,
		boolean ai,
		boolean deletedQuestion,
		boolean deletedPin,
		String pinType
	) {
		long questionAuthorId = insertUser(client, "question-author");
		long pinId = client.sql("""
			INSERT INTO pins (author_id, pin_type, location, address, deleted_at)
			VALUES (
			    :authorId,
			    CAST(:pinType AS pin_type),
			    ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
			    'Seoul',
			    CASE WHEN :deletedPin THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING pin_id
			""")
			.param("authorId", questionAuthorId)
			.param("pinType", pinType)
			.param("deletedPin", deletedPin)
			.query(Long.class)
			.single();
		long questionId = client.sql("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (
			    :pinId,
			    :authorId,
			    'question title',
			    'question content',
			    CASE WHEN :deletedQuestion THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("authorId", questionAuthorId)
			.param("deletedQuestion", deletedQuestion)
			.query(Long.class)
			.single();
		long answerId = ai
			? insertAiAnswer(client, questionId, accepted)
			: insertHumanAnswer(client, questionId, insertUser(client, "answer-author"), accepted);
		return new AnswerFixture(questionId, pinId, answerId);
	}

	private static long insertUser(JdbcClient client, String prefix) {
		long sequence = SEQUENCE.incrementAndGet();
		return client.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, TRUE)
			RETURNING user_id
			""")
			.param("email", prefix + "-" + sequence + "@example.com")
			.param("nickname", prefix + "-" + sequence)
			.query(Long.class)
			.single();
	}

	private static long insertHumanAnswer(JdbcClient client, long questionId, long authorId, boolean accepted) {
		return client.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content, is_accepted)
			VALUES (:questionId, :authorId, FALSE, 'human answer', :accepted)
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.param("authorId", authorId)
			.param("accepted", accepted)
			.query(Long.class)
			.single();
	}

	private static long insertAiAnswer(JdbcClient client, long questionId, boolean accepted) {
		return client.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content, is_accepted)
			VALUES (:questionId, NULL, TRUE, 'AI answer', :accepted)
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.param("accepted", accepted)
			.query(Long.class)
			.single();
	}

	private record AnswerFixture(long questionId, long pinId, long answerId) {
	}

	private record SqlUpdate(String sql, long id) {
	}

	private record RowCounts(long users, long pins, long questions, long answers) {
	}

	private record FunctionContract(
		boolean securityDefiner,
		String language,
		boolean safeSearchPath,
		boolean publicExecuteRevoked,
		String resultType,
		String definition
	) {
	}
}
