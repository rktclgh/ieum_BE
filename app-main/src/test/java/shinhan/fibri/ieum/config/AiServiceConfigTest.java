package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.main.ai.client.AiServiceClient;

class AiServiceConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(AiServiceConfig.class)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	void doesNotCreateAnAiClientWhenReportReviewIsDisabled() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(AiServiceClient.class));
	}

	@Test
	void createsAnAiClientOnlyWhenInternalReviewIsEnabled() {
		contextRunner
			.withPropertyValues(
				"app.ai.report.enabled=true",
				"app.ai.report.base-url=http://10.0.20.15:8080",
				"app.ai.report.allowed-hosts=10.0.20.15"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(AiServiceProperties.class);
				assertThat(context).hasSingleBean(AiServiceClient.class);
			});
	}

	@Test
	void doesNotFollowRedirectResponsesFromTheAiService() throws Exception {
		AtomicInteger reviewRequests = new AtomicInteger();
		AtomicInteger redirectedRequests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ai/v1/internal/reports/1/review", exchange -> {
			reviewRequests.incrementAndGet();
			exchange.getResponseHeaders().add("Location", "/redirect-target");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.createContext("/redirect-target", exchange -> {
			redirectedRequests.incrementAndGet();
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();

		try {
			AiServiceClient client = new AiServiceConfig().aiServiceClient(
				new AiServiceProperties(
					"http://127.0.0.1:" + server.getAddress().getPort(),
					"127.0.0.1",
					Duration.ofSeconds(2),
					Duration.ofSeconds(2)
				),
				new ObjectMapper()
			);

			client.review(new ReportReviewRequest(
				1L,
				UUID.randomUUID(),
				2L,
				"harassment",
				"detail",
				"a".repeat(64),
				List.of()
			));
		}
		finally {
			server.stop(0);
		}

		assertThat(reviewRequests).hasValue(1);
		assertThat(redirectedRequests).hasValue(0);
	}

}
