package shinhan.fibri.ieum.main.file.service;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;

@Service
public class FileTransactionalOps {

	private final FileRepository fileRepository;

	public FileTransactionalOps(FileRepository fileRepository) {
		this.fileRepository = fileRepository;
	}

	@Transactional(readOnly = true)
	public File loadOwned(UUID fileId, Long userId) {
		return fileRepository.findByFileIdAndUploaderId(fileId, userId)
			.orElseThrow(FileNotFoundException::new);
	}

	@Transactional(readOnly = true)
	public File loadUploaded(UUID fileId) {
		return fileRepository.findById(fileId)
			.filter(File::isUploaded)
			.orElseThrow(FileNotFoundException::new);
	}

	@Transactional
	public void finalizeUpload(UUID fileId, String finalKey, OffsetDateTime uploadedAt, String contentType, Long sizeBytes) {
		File file = fileRepository.findById(fileId)
			.orElseThrow(FileNotFoundException::new);
		file.promoteKey(finalKey);
		file.markUploaded(uploadedAt, contentType, sizeBytes);
		fileRepository.save(file);
	}
}
