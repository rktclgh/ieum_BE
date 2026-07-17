package shinhan.fibri.ieum.main.mail;

import java.util.List;
import java.util.Objects;

public record EmailTemplate(
	String subject,
	String category,
	String headline,
	String intro,
	List<Detail> details,
	String notice
) {

	public EmailTemplate {
		subject = Objects.requireNonNull(subject, "subject must not be null");
		category = Objects.requireNonNull(category, "category must not be null");
		headline = Objects.requireNonNull(headline, "headline must not be null");
		intro = Objects.requireNonNull(intro, "intro must not be null");
		details = List.copyOf(Objects.requireNonNull(details, "details must not be null"));
		notice = Objects.requireNonNull(notice, "notice must not be null");
	}

	public record Detail(String label, String value, boolean highlight) {

		public Detail {
			label = Objects.requireNonNull(label, "label must not be null");
			value = Objects.requireNonNull(value, "value must not be null");
		}
	}
}
