package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.io.InputStream;
import java.util.Objects;

public final class KnowledgeSeedPackageImporter {

	private final KnowledgeSeedPackageParser parser;
	private final KnowledgeSeedPackagePreparer preparer;
	private final KnowledgeSeedPersistencePlanFactory planFactory;
	private final KnowledgeSeedPackageStore store;

	public KnowledgeSeedPackageImporter(
		KnowledgeSeedPackageParser parser,
		KnowledgeSeedPackagePreparer preparer,
		KnowledgeSeedPersistencePlanFactory planFactory,
		KnowledgeSeedPackageStore store
	) {
		this.parser = Objects.requireNonNull(parser, "parser must not be null");
		this.preparer = Objects.requireNonNull(preparer, "preparer must not be null");
		this.planFactory = Objects.requireNonNull(planFactory, "planFactory must not be null");
		this.store = Objects.requireNonNull(store, "store must not be null");
	}

	public KnowledgeSeedImportOutcome importPackage(InputStream input) {
		KnowledgeSeedPackage seedPackage = parser.parse(input);
		PreparedKnowledgeSeedPackage preparedPackage = preparer.prepare(seedPackage);
		KnowledgeSeedPersistencePlan plan = planFactory.create(preparedPackage);
		return store.importPlan(plan);
	}
}
