package shinhan.fibri.ieum.main.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpVerificationMailSender implements VerificationMailSender {

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public SmtpVerificationMailSender(
		JavaMailSender mailSender,
		@Value("${spring.mail.username}") String fromAddress
	) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	@Override
	public void sendSignupCode(String email, String code, int expiresInSeconds) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(email);
		message.setSubject("[Ieum] 이메일 인증 코드");
		message.setText("""
			이메일 인증 코드: %s

			이 코드는 %d분 동안 유효합니다.
			""".formatted(code, expiresInSeconds / 60));
		mailSender.send(message);
	}
}
