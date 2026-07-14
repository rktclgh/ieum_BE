package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;

public final class KnowledgeSeedImportRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeSeedImportRunner.class);
	private static final int ORDER = 100;

	private final KnowledgeSeedPackageImporter importer;
	private final Resource packageResource;

	public KnowledgeSeedImportRunner(
		KnowledgeSeedPackageImporter importer,
		Resource packageResource
	) {
		this.importer = Objects.requireNonNull(importer, "importer must not be null");
		this.packageResource = Objects.requireNonNull(packageResource, "packageResource must not be null");
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		KnowledgeSeedImportOutcome outcome;
		try (InputStream input = packageResource.getInputStream()) {
			outcome = importer.importPackage(input);
		}
		log.info(
			"Knowledge seed package import completed: outcome={}, resource={}",
			outcome,
			packageResource.getDescription()
		);
	}

	@Override
	public int getOrder() {
		return ORDER;
	}
}
