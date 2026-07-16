package shinhan.fibri.ieum.main.mail;

public interface UserSuspensionMailSender {

	void send(UserSuspensionEvent event);
}
