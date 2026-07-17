package shinhan.fibri.ieum.main.mail;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;

public record UserSuspensionEvent(
	Long userId,
	String email,
	String reason,
	OffsetDateTime startsAt,
	OffsetDateTime endsAt,
	Locale locale
) {

	public UserSuspensionEvent {
		userId = Objects.requireNonNull(userId, "userId must not be null");
		email = Objects.requireNonNull(email, "email must not be null");
		reason = Objects.requireNonNull(reason, "reason must not be null");
		startsAt = Objects.requireNonNull(startsAt, "startsAt must not be null");
		locale = Objects.requireNonNull(locale, "locale must not be null");
	}
}
