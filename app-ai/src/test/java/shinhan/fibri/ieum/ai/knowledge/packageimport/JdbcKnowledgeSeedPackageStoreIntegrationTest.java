package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcKnowledgeSeedPackageStoreIntegrationTest {

	private static final String DATABASE = "ieum_ai_seed_store";
	private static final String RESOURCE = "/knowledge/korea_long_stay_seed_v0.2.json";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private DataSource dataSource;
	private ExecutorService executor;
	private JdbcClient jdbc;
	private KnowledgeSeedPackageStore store;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		store = new JdbcKnowledgeSeedPackageStore(
			jdbc,
			new DataSourceTransactionManager(dataSource),
			objectMapper
		);
	}

	@AfterEach
	void stopExecutor() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Test
	void importsTheCompleteCanonicalGraphAsOneReadyRevision() throws IOException {
		KnowledgeSeedPersistencePlan plan = canonicalPlan();

		KnowledgeSeedImportOutcome outcome = store.importPlan(plan);

		assertThat(outcome).isEqualTo(KnowledgeSeedImportOutcome.IMPORTED);
		assertThat(count("knowledge_sources")).isEqualTo(20);
		assertThat(count("knowledge_chunks")).isEqualTo(20);
		assertThat(count("knowledge_relations")).isEqualTo(50);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_sources
			WHERE status = 'ready'
			  AND active
			  AND source_type = 'curated'
			  AND anchor_location IS NULL
			  AND question_id IS NULL
			  AND answer_id IS NULL
			  AND created_by = 'knowledge-importer'
			  AND updated_by = 'knowledge-importer'
			  AND metadata ->> 'packageKey' = :packageKey
			  AND metadata ->> 'packageVersion' = :packageVersion
			  AND metadata ->> 'manifestHash' = :manifestHash
			""")
			.param("packageKey", plan.packageKey())
			.param("packageVersion", plan.packageVersion())
			.param("manifestHash", plan.manifestHash())
			.query(Integer.class)
			.single()).isEqualTo(20);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_chunks
			WHERE embedding_model = 'gemini-embedding-2'
			  AND vector_dims(embedding) = 768
			  AND chunk_order = 0
			""").query(Integer.class).single()).isEqualTo(20);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_relations kr
			JOIN knowledge_chunks kc
			  ON kc.source_id = kr.source_id
			 AND kc.chunk_id = kr.evidence_chunk_id
			WHERE kc.chunk_order = 0
			""").query(Integer.class).single()).isEqualTo(50);
		assertThat(jdbc.sql("""
			SELECT valid_until
			FROM knowledge_sources
			WHERE external_ref = 'seed:korea-long-stay-seed:kr-foreigner-registration-90-days'
			""").query(OffsetDateTime.class).single().toInstant())
			.isEqualTo(Instant.parse("2026-10-13T15:00:00Z"));
	}

	@Test
	void returnsNoOpWithoutChangingRowsForAnIdenticalCompletePackage() throws IOException {
		KnowledgeSeedPersistencePlan plan = canonicalPlan();
		store.importPlan(plan);
		List<Long> sourceIds = sourceIds();
		List<Long> chunkIds = chunkIds();
		List<Long> relationIds = relationIds();
		List<String> rowVersions = rowVersions();

		KnowledgeSeedImportOutcome outcome = store.importPlan(withEmbeddingValue(plan, 0.25f));

		assertThat(outcome).isEqualTo(KnowledgeSeedImportOutcome.NO_OP);
		assertThat(sourceIds()).containsExactlyElementsOf(sourceIds);
		assertThat(chunkIds()).containsExactlyElementsOf(chunkIds);
		assertThat(relationIds()).containsExactlyElementsOf(relationIds);
		assertThat(rowVersions()).containsExactlyElementsOf(rowVersions);
		assertThat(count("knowledge_sources")).isEqualTo(20);
		assertThat(count("knowledge_relations")).isEqualTo(50);
	}

	@Test
	void rejectsAReusedPackageVersionWithADifferentManifestHash() throws IOException {
		KnowledgeSeedPersistencePlan plan = canonicalPlan();
		store.importPlan(plan);
		KnowledgeSeedPersistencePlan conflicting = withPackageIdentity(
			plan,
			plan.packageVersion(),
			"f".repeat(64)
		);

		assertThatThrownBy(() -> store.importPlan(conflicting))
			.isInstanceOfSatisfying(KnowledgeSeedImportException.class, exception ->
				assertThat(exception.code())
					.isEqualTo(KnowledgeSeedImportException.Code.PACKAGE_VERSION_HASH_CONFLICT));
		assertThat(count("knowledge_sources")).isEqualTo(20);
		assertThat(count("knowledge_chunks")).isEqualTo(20);
		assertThat(count("knowledge_relations")).isEqualTo(50);
	}

	@ParameterizedTest
	@ValueSource(strings = {"source", "chunk", "relation", "identity"})
	void rejectsAnIncompleteOrMutatedStoredGraphInsteadOfTreatingItAsNoOp(String mutation) throws IOException {
		KnowledgeSeedPersistencePlan plan = canonicalPlan();
		store.importPlan(plan);
		mutateStoredGraph(mutation);

		assertThatThrownBy(() -> store.importPlan(plan))
			.isInstanceOfSatisfying(KnowledgeSeedImportException.class, exception ->
				assertThat(exception.code())
					.isEqualTo(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE));
	}

	@Test
	void atomicallyReplacesActiveLogicalSourcesWithANewPackageRevision() throws IOException {
		KnowledgeSeedPersistencePlan first = canonicalPlan();
		KnowledgeSeedPersistencePlan second = withPackageIdentity(
			first,
			"2026-07-14.1",
			"e".repeat(64)
		);
		second = withoutLastSource(second);
		store.importPlan(first);

		KnowledgeSeedImportOutcome outcome = store.importPlan(second);

		assertThat(outcome).isEqualTo(KnowledgeSeedImportOutcome.IMPORTED);
		int firstRelationCount = relationCount(first);
		int secondRelationCount = relationCount(second);
		assertThat(count("knowledge_sources")).isEqualTo(39);
		assertThat(count("knowledge_chunks")).isEqualTo(39);
		assertThat(count("knowledge_relations")).isEqualTo(firstRelationCount + secondRelationCount);
		assertThat(jdbc.sql("""
			SELECT metadata ->> 'packageVersion' AS package_version,
			       status,
			       active,
			       count(*) AS row_count
			FROM knowledge_sources
			GROUP BY package_version, status, active
			ORDER BY package_version
			""")
			.query((resultSet, rowNumber) -> List.of(
				resultSet.getString("package_version"),
				resultSet.getString("status"),
				Boolean.toString(resultSet.getBoolean("active")),
				Integer.toString(resultSet.getInt("row_count"))
			))
			.list()).containsExactly(
				List.of("2026-07-13.2", "inactive", "false", "20"),
				List.of("2026-07-14.1", "ready", "true", "19")
			);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_sources
			WHERE metadata ->> 'packageVersion' = :packageVersion
			  AND deactivation_reason = 'superseded_by_seed_package'
			""")
			.param("packageVersion", first.packageVersion())
			.query(Integer.class)
			.single()).isEqualTo(20);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_sources
			WHERE external_ref = :removedExternalRef
			  AND active
			""")
			.param("removedExternalRef", first.sources().getLast().externalRef())
			.query(Integer.class)
			.single()).isZero();
		assertThat(store.importPlan(second)).isEqualTo(KnowledgeSeedImportOutcome.NO_OP);
		assertThatThrownBy(() -> store.importPlan(first))
			.isInstanceOfSatisfying(KnowledgeSeedImportException.class, exception ->
				assertThat(exception.code())
					.isEqualTo(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE));
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_sources WHERE active")
			.query(Integer.class)
			.single()).isEqualTo(19);
	}

	@Test
	void rejectsANewSnapshotWhenARemovedPreviousSourceHasCorruptedPackageIdentity() throws IOException {
		KnowledgeSeedPersistencePlan first = canonicalPlan();
		KnowledgeSeedPersistencePlan second = withoutLastSource(withPackageIdentity(
			first,
			"2026-07-14.1",
			"e".repeat(64)
		));
		store.importPlan(first);
		jdbc.sql("""
			UPDATE knowledge_sources
			SET metadata = metadata - 'packageKey'
			WHERE external_ref = :removedExternalRef
			""")
			.param("removedExternalRef", first.sources().getLast().externalRef())
			.update();

		assertThatThrownBy(() -> store.importPlan(second))
			.isInstanceOfSatisfying(KnowledgeSeedImportException.class, exception ->
				assertThat(exception.code())
					.isEqualTo(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE));
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_sources WHERE active")
			.query(Integer.class)
			.single()).isEqualTo(20);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_sources
			WHERE metadata ->> 'packageVersion' = :packageVersion
			""")
			.param("packageVersion", second.packageVersion())
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void rejectsANewSnapshotWhenTheActiveRevisionHasMixedIdentity() throws IOException {
		KnowledgeSeedPersistencePlan first = canonicalPlan();
		KnowledgeSeedPersistencePlan second = withPackageIdentity(
			first,
			"2026-07-14.1",
			"e".repeat(64)
		);
		store.importPlan(first);
		jdbc.sql("""
			UPDATE knowledge_sources
			SET metadata = jsonb_set(
			    jsonb_set(metadata, '{packageVersion}', '"corrupted-version"'::jsonb),
			    '{manifestHash}', to_jsonb(CAST(:manifestHash AS text))
			)
			WHERE source_id = (SELECT min(source_id) FROM knowledge_sources)
			""")
			.param("manifestHash", "f".repeat(64))
			.update();

		assertThatThrownBy(() -> store.importPlan(second))
			.isInstanceOfSatisfying(KnowledgeSeedImportException.class, exception ->
				assertThat(exception.code())
					.isEqualTo(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE));
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_sources WHERE active")
			.query(Integer.class)
			.single()).isEqualTo(20);
	}

	@Test
	void rollsBackTheNewGraphAndOldRevisionDeactivationWhenALateRelationInsertFails() throws IOException {
		KnowledgeSeedPersistencePlan first = canonicalPlan();
		store.importPlan(first);
		KnowledgeSeedPersistencePlan second = withPackageIdentity(
			first,
			"2026-07-14.1",
			"d".repeat(64)
		);
		KnowledgeSeedPersistencePlan invalid = replaceLastRelation(
			second,
			relation -> new KnowledgeSeedPersistencePlan.RelationRow(
				relation.subject(),
				"x".repeat(121),
				relation.object(),
				relation.confidence(),
				relation.evidenceChunkOrder(),
				relation.metadataJson()
			)
		);

		Throwable failure = catchThrowable(() -> store.importPlan(invalid));
		assertThat(failure)
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasRootCauseInstanceOf(SQLException.class);
		assertThat(sqlState(failure)).isEqualTo("22001");
		assertThat(count("knowledge_sources")).isEqualTo(20);
		assertThat(count("knowledge_chunks")).isEqualTo(20);
		assertThat(count("knowledge_relations")).isEqualTo(50);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_sources
			WHERE active
			  AND status = 'ready'
			  AND metadata ->> 'packageVersion' = :packageVersion
			""")
			.param("packageVersion", first.packageVersion())
			.query(Integer.class)
			.single()).isEqualTo(20);
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM knowledge_sources
			WHERE metadata ->> 'packageVersion' = :packageVersion
			""")
			.param("packageVersion", second.packageVersion())
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	@Timeout(20)
	void serializesConcurrentImportsOfTheSamePackageIntoOneImportAndOneNoOp()
		throws IOException, InterruptedException, ExecutionException, SQLException {
		KnowledgeSeedPersistencePlan plan = canonicalPlan();
		executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<KnowledgeSeedImportOutcome>> futures = new ArrayList<>();
		try (Connection blocker = dataSource.getConnection()) {
			blocker.setAutoCommit(false);
			try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
				statement.setLong(1, advisoryLockKey(plan.packageKey()));
				statement.execute();
			}
			for (int index = 0; index < 2; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("concurrent import start timed out");
					}
					return store.importPlan(plan);
				}));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			awaitAdvisoryLockWaiters(2);
			assertThat(count("knowledge_sources")).isZero();
			blocker.commit();
		}

		assertThat(futures).extracting(Future::get)
			.containsExactlyInAnyOrder(KnowledgeSeedImportOutcome.IMPORTED, KnowledgeSeedImportOutcome.NO_OP);
		assertThat(count("knowledge_sources")).isEqualTo(20);
		assertThat(count("knowledge_chunks")).isEqualTo(20);
		assertThat(count("knowledge_relations")).isEqualTo(50);
	}

	private KnowledgeSeedPersistencePlan canonicalPlan() throws IOException {
		KnowledgeSeedPackage seedPackage;
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			if (input == null) {
				throw new IOException("Missing canonical resource " + RESOURCE);
			}
			seedPackage = new KnowledgeSeedPackageParser(objectMapper).parse(input);
		}
		List<PreparedKnowledgeSeedPackage.PreparedSource> sources = seedPackage.sources().stream()
			.map(source -> new PreparedKnowledgeSeedPackage.PreparedSource(
				source,
				new GeminiEmbedding(
					GeminiEmbedding.MODEL,
					Collections.nCopies(GeminiEmbedding.DIMENSIONS, source.sourceKey().hashCode() / 1_000_000_000f)
				)
			))
			.toList();
		return new KnowledgeSeedPersistencePlanFactory(objectMapper)
			.create(new PreparedKnowledgeSeedPackage(seedPackage, sources));
	}

	private KnowledgeSeedPersistencePlan withPackageIdentity(
		KnowledgeSeedPersistencePlan plan,
		String packageVersion,
		String manifestHash
	) {
		List<KnowledgeSeedPersistencePlan.SourceRow> sources = plan.sources().stream()
			.map(source -> new KnowledgeSeedPersistencePlan.SourceRow(
				source.sourceKey(),
				source.externalRef(),
				source.contentHash(),
				source.displayName(),
				source.geoScope(),
				source.regionContextJson(),
				source.validUntilExclusive(),
				withIdentity(source.metadataJson(), packageVersion, manifestHash),
				new KnowledgeSeedPersistencePlan.ChunkRow(
					source.chunk().chunkOrder(),
					source.chunk().content(),
					withIdentity(source.chunk().metadataJson(), packageVersion, null),
					source.chunk().embedding()
				),
				source.relations().stream()
					.map(relation -> new KnowledgeSeedPersistencePlan.RelationRow(
						relation.subject(), relation.predicate(), relation.object(), relation.confidence(),
						relation.evidenceChunkOrder(),
						withIdentity(relation.metadataJson(), packageVersion, null)
					))
					.toList()
			))
			.toList();
		return new KnowledgeSeedPersistencePlan(
			plan.packageKey(), packageVersion, manifestHash, plan.expectedSourceCount(), sources
		);
	}

	private String withIdentity(String json, String packageVersion, String manifestHash) {
		try {
			ObjectNode node = (ObjectNode) objectMapper.readTree(json);
			node.put("packageVersion", packageVersion);
			if (manifestHash != null) {
				node.put("manifestHash", manifestHash);
			}
			return objectMapper.writeValueAsString(node);
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("invalid test JSON", exception);
		}
	}

	private KnowledgeSeedPersistencePlan replaceLastRelation(
		KnowledgeSeedPersistencePlan plan,
		UnaryOperator<KnowledgeSeedPersistencePlan.RelationRow> replacement
	) {
		List<KnowledgeSeedPersistencePlan.SourceRow> sources = new ArrayList<>(plan.sources());
		KnowledgeSeedPersistencePlan.SourceRow source = sources.getLast();
		List<KnowledgeSeedPersistencePlan.RelationRow> relations = new ArrayList<>(source.relations());
		relations.set(relations.size() - 1, replacement.apply(relations.getLast()));
		sources.set(sources.size() - 1, new KnowledgeSeedPersistencePlan.SourceRow(
			source.sourceKey(), source.externalRef(), source.contentHash(), source.displayName(), source.geoScope(),
			source.regionContextJson(), source.validUntilExclusive(), source.metadataJson(), source.chunk(), relations
		));
		return new KnowledgeSeedPersistencePlan(
			plan.packageKey(), plan.packageVersion(), plan.manifestHash(), plan.expectedSourceCount(), sources
		);
	}

	private KnowledgeSeedPersistencePlan withoutLastSource(KnowledgeSeedPersistencePlan plan) {
		int expectedSourceCount = plan.expectedSourceCount() - 1;
		List<KnowledgeSeedPersistencePlan.SourceRow> sources = plan.sources().subList(0, expectedSourceCount).stream()
			.map(source -> new KnowledgeSeedPersistencePlan.SourceRow(
				source.sourceKey(), source.externalRef(), source.contentHash(), source.displayName(), source.geoScope(),
				source.regionContextJson(), source.validUntilExclusive(),
				withExpectedSourceCount(source.metadataJson(), expectedSourceCount),
				source.chunk(), source.relations()
			))
			.toList();
		return new KnowledgeSeedPersistencePlan(
			plan.packageKey(), plan.packageVersion(), plan.manifestHash(), expectedSourceCount, sources
		);
	}

	private String withExpectedSourceCount(String json, int expectedSourceCount) {
		try {
			ObjectNode node = (ObjectNode) objectMapper.readTree(json);
			node.put("expectedSourceCount", expectedSourceCount);
			return objectMapper.writeValueAsString(node);
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("invalid test JSON", exception);
		}
	}

	private int relationCount(KnowledgeSeedPersistencePlan plan) {
		return plan.sources().stream().mapToInt(source -> source.relations().size()).sum();
	}

	private KnowledgeSeedPersistencePlan withEmbeddingValue(
		KnowledgeSeedPersistencePlan plan,
		float value
	) {
		List<KnowledgeSeedPersistencePlan.SourceRow> sources = plan.sources().stream()
			.map(source -> new KnowledgeSeedPersistencePlan.SourceRow(
				source.sourceKey(), source.externalRef(), source.contentHash(), source.displayName(), source.geoScope(),
				source.regionContextJson(), source.validUntilExclusive(), source.metadataJson(),
				new KnowledgeSeedPersistencePlan.ChunkRow(
					source.chunk().chunkOrder(), source.chunk().content(), source.chunk().metadataJson(),
					new GeminiEmbedding(
						GeminiEmbedding.MODEL,
						Collections.nCopies(GeminiEmbedding.DIMENSIONS, value)
					)
				),
				source.relations()
			))
			.toList();
		return new KnowledgeSeedPersistencePlan(
			plan.packageKey(), plan.packageVersion(), plan.manifestHash(), plan.expectedSourceCount(), sources
		);
	}

	private void awaitAdvisoryLockWaiters(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int waiters = jdbc.sql("""
				SELECT count(*)
				FROM pg_locks
				WHERE locktype = 'advisory'
				  AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
				  AND NOT granted
				""").query(Integer.class).single();
			if (waiters >= expected) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("expected " + expected + " waiting advisory locks");
	}

	private long advisoryLockKey(String packageKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(("ieum:seed-package:" + packageKey).getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.wrap(digest).getLong();
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private void mutateStoredGraph(String mutation) {
		switch (mutation) {
			case "source" -> jdbc.sql("""
				UPDATE knowledge_sources
				SET display_name = display_name || '-mutated'
				WHERE source_id = (SELECT min(source_id) FROM knowledge_sources)
				""").update();
			case "chunk" -> jdbc.sql("""
				DELETE FROM knowledge_chunks
				WHERE chunk_id = (SELECT min(chunk_id) FROM knowledge_chunks)
				""").update();
			case "relation" -> jdbc.sql("""
				DELETE FROM knowledge_relations
				WHERE relation_id = (SELECT min(relation_id) FROM knowledge_relations)
				""").update();
			case "identity" -> jdbc.sql("""
				UPDATE knowledge_sources
				SET metadata = metadata - 'packageKey'
				""").update();
			default -> throw new IllegalArgumentException("unknown mutation " + mutation);
		}
	}

	private List<Long> sourceIds() {
		return jdbc.sql("SELECT source_id FROM knowledge_sources ORDER BY source_id")
			.query(Long.class)
			.list();
	}

	private List<Long> chunkIds() {
		return jdbc.sql("SELECT chunk_id FROM knowledge_chunks ORDER BY chunk_id")
			.query(Long.class)
			.list();
	}

	private List<Long> relationIds() {
		return jdbc.sql("SELECT relation_id FROM knowledge_relations ORDER BY relation_id")
			.query(Long.class)
			.list();
	}

	private List<String> rowVersions() {
		return jdbc.sql("""
			SELECT row_kind || ':' || row_id::text || ':' || row_version AS version
			FROM (
			    SELECT 'source' AS row_kind, source_id AS row_id, xmin::text AS row_version
			    FROM knowledge_sources
			    UNION ALL
			    SELECT 'chunk', chunk_id, xmin::text
			    FROM knowledge_chunks
			    UNION ALL
			    SELECT 'relation', relation_id, xmin::text
			    FROM knowledge_relations
			) rows
			ORDER BY row_kind, row_id
			""").query(String.class).list();
	}

	private String sqlState(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof SQLException sqlException) {
				return sqlException.getSQLState();
			}
			current = current.getCause();
		}
		return null;
	}

	private int count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
	}
}
