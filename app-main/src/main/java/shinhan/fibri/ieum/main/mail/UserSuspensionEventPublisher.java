package shinhan.fibri.ieum.main.mail;

import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;

public interface UserSuspensionEventPublisher {

	void publish(User user, UserSanction sanction);
}
