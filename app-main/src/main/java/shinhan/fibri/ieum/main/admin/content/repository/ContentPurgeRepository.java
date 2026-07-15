package shinhan.fibri.ieum.main.admin.content.repository;

import java.time.OffsetDateTime;

public interface ContentPurgeRepository {

	ContentPurgeChunk purgeChunk(OffsetDateTime cutoff, int limit);
}
