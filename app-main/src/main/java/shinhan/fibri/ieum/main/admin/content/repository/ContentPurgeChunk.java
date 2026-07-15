package shinhan.fibri.ieum.main.admin.content.repository;

import java.util.List;

public record ContentPurgeChunk(
	int purgedCount,
	List<String> s3Keys
) {

	public ContentPurgeChunk {
		s3Keys = List.copyOf(s3Keys);
	}

	public static ContentPurgeChunk empty() {
		return new ContentPurgeChunk(0, List.of());
	}

	public boolean isEmpty() {
		return purgedCount == 0;
	}
}
