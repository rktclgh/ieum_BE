package shinhan.fibri.ieum.ai.knowledge.packageimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class KnowledgeSeedManifestHasher {

	private final ObjectMapper objectMapper = new ObjectMapper()
		.disable(SerializationFeature.INDENT_OUTPUT);

	public String hash(JsonNode rawPackage) {
		if (rawPackage == null || !rawPackage.isObject()) {
			throw new KnowledgeSeedPackageValidationException("knowledge package root must be an object");
		}
		ObjectNode copy = ((ObjectNode) rawPackage).deepCopy();
		copy.remove("manifestHash");
		JsonNode canonical = canonicalize(copy);
		try {
			byte[] payload = objectMapper.writeValueAsBytes(canonical);
			return HexFormat.of().formatHex(sha256(payload));
		}
		catch (JsonProcessingException exception) {
			throw new KnowledgeSeedPackageValidationException("knowledge package manifest cannot be serialized", exception);
		}
	}

	private JsonNode canonicalize(JsonNode node) {
		if (node.isObject()) {
			ObjectNode sorted = objectMapper.createObjectNode();
			List<String> names = new ArrayList<>();
			node.fieldNames().forEachRemaining(names::add);
			names.stream().sorted().forEach(name -> sorted.set(name, canonicalize(node.get(name))));
			return sorted;
		}
		if (node.isArray()) {
			ArrayNode array = objectMapper.createArrayNode();
			for (JsonNode element : node) {
				array.add(canonicalize(element));
			}
			return array;
		}
		return node.deepCopy();
	}

	private byte[] sha256(byte[] payload) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(payload);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is not available", exception);
		}
	}
}
