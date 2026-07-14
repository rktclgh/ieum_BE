package shinhan.fibri.ieum.main.user.service;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.service.FileObjectKeys;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.FileStorage;

@Service
public class ProfileFileCleanupService {

	private static final Logger log = LoggerFactory.getLogger(ProfileFileCleanupService.class);

	private final UserRepository userRepository;
	private final FileRepository fileRepository;
	private final FileStorage fileStorage;
	private final TransactionTemplate cleanupTransaction;

	@Autowired
	public ProfileFileCleanupService(
		UserRepository userRepository,
		FileRepository fileRepository,
		FileStorage fileStorage,
		PlatformTransactionManager transactionManager
	) {
		this(userRepository, fileRepository, fileStorage, new TransactionTemplate(transactionManager));
	}

	ProfileFileCleanupService(
		UserRepository userRepository,
		FileRepository fileRepository,
		FileStorage fileStorage,
		TransactionTemplate cleanupTransaction
	) {
		this.userRepository = userRepository;
		this.fileRepository = fileRepository;
		this.fileStorage = fileStorage;
		this.cleanupTransaction = cleanupTransaction;
		this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void cleanupProfileFile(UUID fileId) {
		Optional<String> deletedS3Key = deleteFileRow(fileId);
		deletedS3Key.ifPresent(this::deleteS3ObjectsLogOnly);
	}

	private Optional<String> deleteFileRow(UUID fileId) {
		try {
			Optional<String> deletedKey = cleanupTransaction.execute(status -> {
				if (userRepository.existsByProfileFileIdAndDeletedAtIsNull(fileId)) {
					return Optional.empty();
				}
				return fileRepository.findById(fileId)
					.map(file -> {
						String s3Key = file.getS3Key();
						fileRepository.delete(file);
						return s3Key;
					});
			});
			return deletedKey == null ? Optional.empty() : deletedKey;
		} catch (RuntimeException exception) {
			log.warn("Failed to delete unreferenced profile file row. fileId={}", fileId, exception);
			return Optional.empty();
		}
	}

	private void deleteS3ObjectsLogOnly(String s3Key) {
		try {
			fileStorage.delete(s3Key);
			fileStorage.delete(FileObjectKeys.variantKey(s3Key, FileVariant.DISPLAY));
			fileStorage.delete(FileObjectKeys.variantKey(s3Key, FileVariant.THUMB));
		} catch (RuntimeException exception) {
			log.warn("Failed to delete unreferenced profile file objects. s3Key={}", s3Key, exception);
		}
	}
}
