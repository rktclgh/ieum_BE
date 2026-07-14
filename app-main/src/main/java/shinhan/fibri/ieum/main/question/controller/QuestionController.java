package shinhan.fibri.ieum.main.question.controller;

import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.question.dto.CursorPage;
import shinhan.fibri.ieum.main.question.dto.MyQuestionItem;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.main.question.dto.QuestionDetailResponse;
import shinhan.fibri.ieum.main.question.service.QuestionService;

@RestController
@RequestMapping("/api/v1/questions")
@RequiredArgsConstructor
public class QuestionController {

	private final QuestionService questionService;

	@PostMapping
	public ResponseEntity<QuestionDetailResponse> create(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@Valid @RequestBody QuestionCreateRequest request
	) {
		QuestionDetailResponse response = questionService.create(principal, request);
		return ResponseEntity.created(URI.create("/api/v1/questions/" + response.questionId()))
			.body(response);
	}

	@GetMapping("/{questionId}")
	public ResponseEntity<QuestionDetailResponse> getDetail(@PathVariable Long questionId) {
		return ResponseEntity.ok(questionService.getDetail(questionId));
	}

	@GetMapping("/me")
	public ResponseEntity<CursorPage<MyQuestionItem>> listMine(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam(required = false) String cursor,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(questionService.listMine(principal, cursor, size));
	}

	@DeleteMapping("/{questionId}")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long questionId
	) {
		questionService.delete(principal, questionId);
		return ResponseEntity.noContent().build();
	}
}
