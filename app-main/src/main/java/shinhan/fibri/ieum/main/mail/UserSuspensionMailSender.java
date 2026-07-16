package shinhan.fibri.ieum.main.mail;

import java.util.Locale;

public interface UserSuspensionMailSender {

	void send(UserSuspensionEvent event, Locale locale);
}
