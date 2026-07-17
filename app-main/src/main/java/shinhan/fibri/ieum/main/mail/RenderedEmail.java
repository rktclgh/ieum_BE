package shinhan.fibri.ieum.main.mail;

import java.util.Objects;

public record RenderedEmail(String subject, String plainText, String htmlText) {

	public RenderedEmail {
		subject = Objects.requireNonNull(subject, "subject must not be null");
		plainText = Objects.requireNonNull(plainText, "plainText must not be null");
		htmlText = Objects.requireNonNull(htmlText, "htmlText must not be null");
	}
}
