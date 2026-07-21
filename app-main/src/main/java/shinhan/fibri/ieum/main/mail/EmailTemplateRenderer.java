package shinhan.fibri.ieum.main.mail;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class EmailTemplateRenderer {

	private static final String FONT_FAMILY = "'Apple SD Gothic Neo', 'Malgun Gothic', 'Noto Sans KR', Arial, sans-serif";
	private static final String PRIMARY_COLOR = "#FC7045";
	private static final String PRIMARY_TINT = "#FFF0EB";
	private static final String PRIMARY_BORDER = "#FFD9CB";
	private static final String PAGE_BACKGROUND = "#FFF8F5";
	static final String LOGO_CONTENT_ID = "ieum-logo";

	private final MessageSource messageSource;

	public EmailTemplateRenderer(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	public RenderedEmail render(EmailTemplate template, Locale locale) {
		Locale resolvedLocale = locale == null ? Locale.KOREAN : locale;
		String footer = messageSource.getMessage("mail.template.footer", null, resolvedLocale);
		return new RenderedEmail(
			template.subject(),
			renderPlainText(template, footer),
			renderHtml(template, footer, resolvedLocale)
		);
	}

	private String renderPlainText(EmailTemplate template, String footer) {
		StringBuilder body = new StringBuilder();
		body.append("IEUM | ").append(template.category()).append("\n\n");
		body.append(template.headline()).append("\n\n");
		body.append(template.intro()).append("\n\n");
		for (EmailTemplate.Detail detail : template.details()) {
			body.append(detail.label()).append(": ").append(detail.value()).append("\n\n");
		}
		body.append(template.notice()).append("\n\n");
		body.append("--\n").append(footer);
		return body.toString();
	}

	private String renderHtml(EmailTemplate template, String footer, Locale locale) {
		StringBuilder details = new StringBuilder();
		for (EmailTemplate.Detail detail : template.details()) {
			String background = detail.highlight() ? PRIMARY_TINT : "#FFFFFF";
			details.append("<tr><td align=\"center\" style=\"padding:16px 0;border-top:1px solid ").append(PRIMARY_BORDER).append(";text-align:center;\">")
				.append("<div style=\"font-size:12px;line-height:18px;color:#667085;margin-bottom:6px;text-align:center;\">")
				.append(escape(detail.label()))
				.append("</div><div style=\"background:").append(background)
				.append(";border-radius:4px;padding:12px 14px;font-size:15px;line-height:24px;color:#1F2933;white-space:normal;text-align:center;\">")
				.append(escapeMultiline(detail.value()))
				.append("</div></td></tr>");
		}
		String language = locale.getLanguage().isBlank() ? "ko" : locale.getLanguage();
		return "<!doctype html><html lang=\"" + escape(language) + "\"><head>"
			+ "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
			+ "</head><body style=\"margin:0;padding:0;background:" + PAGE_BACKGROUND + ";font-family:" + FONT_FAMILY + ";color:#1F2933;\">"
			+ "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;\">"
			+ escape(template.headline()) + "</div>"
			+ "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:" + PAGE_BACKGROUND + ";\"><tr><td align=\"center\" style=\"padding:32px 16px;\">"
			+ "<table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" style=\"width:100%;max-width:600px;background:#FFFFFF;\">"
			+ "<tr><td align=\"center\" style=\"padding:20px 28px;background:#FFFFFF;border-top:6px solid " + PRIMARY_COLOR + ";text-align:center;\"><img src=\"cid:" + LOGO_CONTENT_ID + "\" width=\"108\" height=\"56\" alt=\"이음\" style=\"display:block;width:108px;height:56px;margin:0 auto;border:0;outline:none;text-decoration:none;\">"
			+ "<div style=\"font-size:12px;line-height:18px;color:" + PRIMARY_COLOR + ";margin-top:10px;font-weight:700;text-align:center;\">" + escape(template.category()) + "</div></td></tr>"
			+ "<tr><td align=\"center\" style=\"padding:32px 28px 12px;text-align:center;\"><h1 style=\"font-size:22px;line-height:30px;margin:0;color:" + PRIMARY_COLOR + ";font-weight:700;letter-spacing:0;text-align:center;\">"
			+ escape(template.headline()) + "</h1><p style=\"font-size:15px;line-height:24px;margin:14px 0 0;color:#1F2933;text-align:center;\">"
			+ escapeMultiline(template.intro()) + "</p></td></tr>"
			+ "<tr><td style=\"padding:12px 28px 0;\"><table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">"
			+ details + "</table></td></tr>"
			+ "<tr><td align=\"center\" style=\"padding:20px 28px 28px;text-align:center;\"><p style=\"font-size:13px;line-height:21px;margin:0;color:#667085;text-align:center;\">"
			+ escapeMultiline(template.notice()) + "</p></td></tr>"
			+ "<tr><td align=\"center\" style=\"padding:18px 28px;background:" + PAGE_BACKGROUND + ";border-top:1px solid " + PRIMARY_BORDER + ";text-align:center;\"><p style=\"font-size:12px;line-height:18px;margin:0;color:#667085;text-align:center;\">"
			+ escape(footer) + "</p></td></tr></table></td></tr></table></body></html>";
	}

	private String escapeMultiline(String value) {
		return escape(value).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
	}

	private String escape(String value) {
		return HtmlUtils.htmlEscape(value);
	}
}
