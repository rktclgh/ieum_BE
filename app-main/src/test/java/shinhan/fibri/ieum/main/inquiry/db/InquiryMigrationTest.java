package shinhan.fibri.ieum.main.inquiry.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InquiryMigrationTest {

	@Test
	void clearsAnswerAuthorWhenTheAnsweringUserIsDeleted() throws IOException {
		InputStream input = getClass().getClassLoader()
			.getResourceAsStream("db/migration/V20260713_02_set_inquiry_answered_by_on_delete.sql");

		assertThat(input).isNotNull();
		String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

		assertThat(sql).contains("FOREIGN KEY (answered_by)");
		assertThat(sql).contains("ON DELETE SET NULL");
	}
}
