package shinhan.fibri.ieum.ai.embedding;

import java.util.List;
import java.util.Objects;

public record GeminiEmbedding(String model, List<Float> values) {

	public static final String MODEL = "gemini-embedding-2";
	public static final int DIMENSIONS = 768;

	public GeminiEmbedding {
		if (!MODEL.equals(model)) {
			throw new IllegalArgumentException("model must be " + MODEL);
		}
		Objects.requireNonNull(values, "values must not be null");
		if (values.size() != DIMENSIONS) {
			throw new IllegalArgumentException("values must contain exactly " + DIMENSIONS + " dimensions");
		}
		for (Float value : values) {
			if (value == null || !Float.isFinite(value)) {
				throw new IllegalArgumentException("values must contain only finite numbers");
			}
		}
		values = List.copyOf(values);
	}
}
