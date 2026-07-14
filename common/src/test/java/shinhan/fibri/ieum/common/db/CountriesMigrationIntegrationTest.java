package shinhan.fibri.ieum.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class CountriesMigrationIntegrationTest {

	@Test
	void addsUsersNationalityForeignKeyWhenAnotherTableUsesSameConstraintName() {
		String database = "ieum_country_v7_constraint";
		CanonicalPostgresContainer.recreateDatabase(database);
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(database));

		jdbc.sql("CREATE TABLE users (user_id BIGINT PRIMARY KEY, nationality VARCHAR(2))").update();
		jdbc.sql("CREATE TABLE unrelated_records (record_id BIGINT PRIMARY KEY)").update();
		jdbc.sql("""
			ALTER TABLE unrelated_records
			ADD CONSTRAINT fk_users_nationality CHECK (record_id > 0)
			""").update();

		SqlScriptRunner.run(database, "migrations/v7_countries.sql");

		assertThat(jdbc.sql("""
			SELECT EXISTS (
				SELECT 1
				FROM pg_constraint
				WHERE conname = 'fk_users_nationality'
				  AND conrelid = 'users'::regclass
				  AND contype = 'f'
			)
			""").query(Boolean.class).single()).isTrue();
	}
}
