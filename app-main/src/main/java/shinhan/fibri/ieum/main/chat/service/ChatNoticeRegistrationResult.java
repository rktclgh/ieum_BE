package shinhan.fibri.ieum.main.chat.service;

import shinhan.fibri.ieum.main.chat.dto.ChatNoticeResponse;

public record ChatNoticeRegistrationResult(
	ChatNoticeResponse notice,
	boolean created
) {
}
