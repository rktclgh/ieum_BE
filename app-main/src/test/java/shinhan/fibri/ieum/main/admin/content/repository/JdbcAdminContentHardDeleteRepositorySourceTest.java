package shinhan.fibri.ieum.main.admin.content.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdbcAdminContentHardDeleteRepositorySourceTest {

	@Test
	void hardDeleteLocksTargetChatRoomsBeforeCandidateFileSnapshot() throws Exception {
		Path sourcePath = Path.of(
			"app-main/src/main/java/shinhan/fibri/ieum/main/admin/content/repository/JdbcAdminContentHardDeleteRepository.java"
		);
		if (!Files.exists(sourcePath)) {
			sourcePath = Path.of(
				"src/main/java/shinhan/fibri/ieum/main/admin/content/repository/JdbcAdminContentHardDeleteRepository.java"
			);
		}
		String source = Files.readString(sourcePath);
		int roomLockIndex = source.indexOf("lockTargetChatRooms(type, target.contentId())");
		int candidateSnapshotIndex = source.indexOf("selectCandidateFiles(type, target.contentId())");

		assertThat(roomLockIndex).isGreaterThanOrEqualTo(0);
		assertThat(candidateSnapshotIndex).isGreaterThanOrEqualTo(0);
		assertThat(roomLockIndex).isLessThan(candidateSnapshotIndex);
		assertThat(source).contains("SELECT room_id FROM chat_rooms WHERE question_id = :id FOR UPDATE");
		assertThat(source).contains("SELECT room_id FROM chat_rooms WHERE meeting_id = :id FOR UPDATE");
		assertThat(source).contains("WHERE room_id IN (SELECT room_id FROM target_rooms)");
	}
}
