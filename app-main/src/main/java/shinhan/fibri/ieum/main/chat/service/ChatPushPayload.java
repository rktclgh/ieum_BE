package shinhan.fibri.ieum.main.chat.service;

public record ChatPushPayload(
	int version,
	String kind,
	String title,
	String body,
	String url,
	String tag
) {
}
