package shinhan.fibri.ieum.main.file.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.file.storage.FileStorage;

@Service
@RequiredArgsConstructor
public class S3FileDeletionService {

	private static final Logger log = LoggerFactory.getLogger(S3FileDeletionService.class);

	private final FileStorage fileStorage;

	public void deleteOriginAndVariantsLogOnly(String originKey) {
		deleteLogOnly(originKey);
		deleteVariantLogOnly(originKey, FileVariant.DISPLAY);
		deleteVariantLogOnly(originKey, FileVariant.THUMB);
	}

	private void deleteVariantLogOnly(String originKey, FileVariant variant) {
		try {
			fileStorage.delete(FileObjectKeys.variantKey(originKey, variant));
		} catch (RuntimeException exception) {
			log.warn("Failed to delete file variant object. s3Key={}, variant={}", originKey, variant, exception);
		}
	}

	private void deleteLogOnly(String s3Key) {
		try {
			fileStorage.delete(s3Key);
		} catch (RuntimeException exception) {
			log.warn("Failed to delete file object. s3Key={}", s3Key, exception);
		}
	}
}
