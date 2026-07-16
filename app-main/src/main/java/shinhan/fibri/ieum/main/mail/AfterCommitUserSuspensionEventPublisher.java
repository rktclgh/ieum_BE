package shinhan.fibri.ieum.main.mail;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;

@Component
public class AfterCommitUserSuspensionEventPublisher implements UserSuspensionEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(AfterCommitUserSuspensionEventPublisher.class);

	private final AfterCommitMailTaskScheduler mailTaskScheduler;
	private final UserMailLocaleResolver userMailLocaleResolver;
	private final UserSuspensionMailSender mailSender;

	public AfterCommitUserSuspensionEventPublisher(
		AfterCommitMailTaskScheduler mailTaskScheduler,
		UserMailLocaleResolver userMailLocaleResolver,
		UserSuspensionMailSender mailSender
	) {
		this.mailTaskScheduler = mailTaskScheduler;
		this.userMailLocaleResolver = userMailLocaleResolver;
		this.mailSender = mailSender;
	}

	@Override
	public void publish(User user, UserSanction sanction) {
		User sourceUser = Objects.requireNonNull(user, "user must not be null");
		UserSanction sourceSanction = Objects.requireNonNull(sanction, "sanction must not be null");
		UserSuspensionEvent event = new UserSuspensionEvent(
			sourceUser.getId(),
			sourceUser.getEmail(),
			sourceSanction.getReason(),
			sourceSanction.getStartsAt(),
			sourceSanction.getEndsAt()
		);
		mailTaskScheduler.executeAfterCommit("user_suspension_email", event.userId(), () -> send(event));
	}

	private void send(UserSuspensionEvent event) {
		try {
			mailSender.send(event, userMailLocaleResolver.resolve(event.userId()));
		} catch (RuntimeException exception) {
			log.error(
				"event=user_suspension_email_failed userId={} failureType={}",
				event.userId(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
