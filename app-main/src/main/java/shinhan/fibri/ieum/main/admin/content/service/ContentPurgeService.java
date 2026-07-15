package shinhan.fibri.ieum.main.admin.content.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.admin.content.repository.ContentPurgeChunk;
import shinhan.fibri.ieum.main.admin.content.repository.ContentPurgeRepository;
import shinhan.fibri.ieum.main.file.service.FileObjectKeys;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.FileStorage;

@Service
public class ContentPurgeService {

	private static final Logger log = LoggerFactory.getLogger(ContentPurgeService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final int CHUNK_SIZE = 500;
	private static final int MAX_CHUNKS_PER_RUN = 100;
	private static final int RETENTION_DAYS = 90;

	private final ContentPurgeRepository repository;
	private final FileStorage fileStorage;
	private final Clock clock;

	public ContentPurgeService(ContentPurgeRepository repository, FileStorage fileStorage) {
		this(repository, fileStorage, Clock.system(KST));
	}

	ContentPurgeService(ContentPurgeRepository repository, FileStorage fileStorage, Clock clock) {
		this.repository = repository;
		this.fileStorage = fileStorage;
		this.clock = clock;
	}

	public int purgeExpiredQuestionContent() {
		OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
		int purged = 0;
		for (int chunk = 0; chunk < MAX_CHUNKS_PER_RUN; chunk++) {
			ContentPurgeChunk result;
			try {
				result = repository.purgeChunk(cutoff, CHUNK_SIZE);
			} catch (RuntimeException exception) {
				log.error(
					"Failed to purge content chunk. chunkIndex={}, cutoff={}, chunkSize={}",
					chunk,
					cutoff,
					CHUNK_SIZE,
					exception
				);
				continue;
			}
			if (result.isEmpty()) {
				break;
			}
			purged += result.purgedCount();
			deleteS3Objects(result.s3Keys());
			if (chunk == MAX_CHUNKS_PER_RUN - 1 && result.purgedCount() >= CHUNK_SIZE) {
				log.warn(
					"Content purge reached max chunks with a full final chunk. cutoff={}, chunkSize={}, maxChunks={}",
					cutoff,
					CHUNK_SIZE,
					MAX_CHUNKS_PER_RUN
				);
			}
		}
		return purged;
	}

	private void deleteS3Objects(List<String> s3Keys) {
		for (String s3Key : s3Keys) {
			deleteLogOnly(s3Key);
			deleteLogOnly(FileObjectKeys.variantKey(s3Key, FileVariant.DISPLAY));
			deleteLogOnly(FileObjectKeys.variantKey(s3Key, FileVariant.THUMB));
		}
	}

	private void deleteLogOnly(String s3Key) {
		try {
			fileStorage.delete(s3Key);
		} catch (RuntimeException exception) {
			log.warn("Failed to delete purged content file object. s3Key={}", s3Key, exception);
		}
	}
}
