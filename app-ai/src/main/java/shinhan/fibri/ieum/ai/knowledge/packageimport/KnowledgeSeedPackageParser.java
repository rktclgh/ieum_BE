package shinhan.fibri.ieum.ai.knowledge.packageimport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class KnowledgeSeedPackageParser {

	private final ObjectMapper objectMapper;
	private final KnowledgeSeedPackageValidator validator;

	public KnowledgeSeedPackageParser(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
		this.objectMapper.coercionConfigDefaults()
			.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
		this.validator = new KnowledgeSeedPackageValidator(new KnowledgeSeedManifestHasher());
	}

	public KnowledgeSeedPackage parse(InputStream input) {
		if (input == null) {
			throw new KnowledgeSeedPackageValidationException("knowledge package input must not be null");
		}
		try {
			JsonNode rawPackage = objectMapper.readTree(input);
			if (rawPackage == null || !rawPackage.isObject()) {
				throw new KnowledgeSeedPackageValidationException("knowledge package root must be an object");
			}
			validator.validateRawShape(rawPackage);
			KnowledgeSeedPackage seedPackage = objectMapper.treeToValue(rawPackage, KnowledgeSeedPackage.class);
			validator.validate(rawPackage, seedPackage);
			return seedPackage;
		}
		catch (KnowledgeSeedPackageValidationException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw new KnowledgeSeedPackageValidationException("knowledge package is invalid", exception);
		}
	}
}
