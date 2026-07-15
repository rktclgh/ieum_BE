package shinhan.fibri.ieum.main.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.chat.domain.RoomType;

class ChatRoomListEventTest {

	private final ObjectMapper objectMapper = new ObjectMapper()
		.registerModule(new JavaTimeModule());

	@Test
	void upsertSerializesRoomEnvelopeWithoutTopLevelRoomId() throws Exception {
		ChatRoomSummaryResponse room = roomSummary();

		JsonNode json = objectMapper.valueToTree(ChatRoomListEvent.upsert(room));

		assertThat(json.get("type").asText()).isEqualTo("upsert");
		assertThat(json.has("room")).isTrue();
		assertThat(json.get("room").get("roomId").asLong()).isEqualTo(100L);
		assertThat(json.has("roomId")).isFalse();
	}

	@Test
	void removeSerializesRoomIdEnvelopeWithoutRoom() {
		JsonNode json = objectMapper.valueToTree(ChatRoomListEvent.remove(100L));

		assertThat(json.get("type").asText()).isEqualTo("remove");
		assertThat(json.get("roomId").asLong()).isEqualTo(100L);
		assertThat(json.has("room")).isFalse();
	}

	private ChatRoomSummaryResponse roomSummary() {
		return new ChatRoomSummaryResponse(
			100L,
			RoomType.direct,
			null,
			null,
			null,
			true,
			true,
			3L,
			new ChatMessageResponse(
				501L,
				100L,
				42L,
				"sender",
				"hello",
				null,
				OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
			)
		);
	}
}
