package shinhan.fibri.ieum.main.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

class EmailTemplateRendererTest {

	@Test
	void rendersACommonAccessibleTemplateAndEscapesDynamicHtml() {
		EmailTemplateRenderer renderer = new EmailTemplateRenderer(messageSource());

		RenderedEmail email = renderer.render(
			new EmailTemplate(
				"[Ieum] 문의 답변",
				"문의 답변",
				"문의 답변이 도착했습니다",
				"운영팀의 답변을 확인해 주세요.",
				List.of(
					new EmailTemplate.Detail("문의 제목", "<script>alert('xss')</script>", false),
					new EmailTemplate.Detail("운영팀 답변", "첫 줄\n둘째 줄", true)
				),
				"추가 문의는 앱에서 확인해 주세요."
			),
			Locale.KOREAN
		);

		assertThat(email.subject()).isEqualTo("[Ieum] 문의 답변");
		assertThat(email.plainText())
			.contains("IEUM")
			.contains("<script>alert('xss')</script>")
			.contains("첫 줄\n둘째 줄")
			.contains("본 메일은 발신 전용입니다.");
		assertThat(email.htmlText())
			.contains("<html lang=\"ko\">")
			.contains("src=\"cid:ieum-logo\"")
			.contains("alt=\"이음\"")
			.contains("#FC7045")
			.contains("text-align:center")
			.contains("margin:0 auto")
			.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;")
			.contains("첫 줄<br>둘째 줄")
			.doesNotContain("#123D35")
			.doesNotContain("<script>");
	}

	private StaticMessageSource messageSource() {
		StaticMessageSource messageSource = new StaticMessageSource();
		messageSource.addMessage("mail.template.footer", Locale.KOREAN, "본 메일은 발신 전용입니다.");
		return messageSource;
	}
}
