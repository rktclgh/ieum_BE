package shinhan.fibri.ieum.main.file.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.file.dto.FileCompleteResponse;
import shinhan.fibri.ieum.main.file.dto.FilePresignRequest;
import shinhan.fibri.ieum.main.file.dto.FilePresignResponse;
import shinhan.fibri.ieum.main.file.dto.FileStreamResponse;
import shinhan.fibri.ieum.main.file.service.FileService;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

	private static final String IMMUTABLE_IMAGE_CACHE = "public, max-age=31536000, immutable";

	private final FileService fileService;

	@PostMapping("/presign")
	public ResponseEntity<FilePresignResponse> presign(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestBody FilePresignRequest request
	) {
		return ResponseEntity.ok(fileService.createPresign(principal, request));
	}

	@PostMapping("/{fileId}/complete")
	public ResponseEntity<FileCompleteResponse> complete(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable UUID fileId
	) {
		return ResponseEntity.ok(fileService.complete(principal, fileId));
	}

	@GetMapping("/{fileId}")
	public ResponseEntity<StreamingResponseBody> stream(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable UUID fileId,
		@RequestParam(name = "v", required = false) String variant
	) {
		FileStreamResponse response = fileService.stream(principal, fileId, variant);
		StreamingResponseBody body = outputStream -> {
			try (var inputStream = response.body()) {
				inputStream.transferTo(outputStream);
			}
		};
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(response.contentType()))
			.contentLength(response.contentLength())
			.header(HttpHeaders.CACHE_CONTROL, IMMUTABLE_IMAGE_CACHE)
			.header("X-Content-Type-Options", "nosniff")
			.body(body);
	}
}
