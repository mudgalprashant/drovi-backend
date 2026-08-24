package com.pm.drovi_backend.web;

import com.pm.drovi_backend.generation.clarify.Clarification;
import com.pm.drovi_backend.generation.clarify.ClarificationService;
import com.pm.drovi_backend.identity.DroviPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The doubts a generation had, and the answers to them.
 *
 * <p>Generation stops on an open question rather than guessing, so these routes are on the
 * critical path: until every question is answered — or explicitly handed back with
 * {@code /assume} — the sandbox is not built.
 *
 * <p>Answered questions are <strong>kept</strong>. Three weeks later, when a sandbox behaves
 * oddly, "we assumed status = BLOCKED because you did not say" is something a user needs to be
 * able to find, not something to reconstruct from a transcript.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/clarifications")
@RequiredArgsConstructor
class ClarificationController {

    private final ClarificationService clarifications;

    /** Either pick one of the offered options, or write your own answer. */
    record AnswerRequest(@Size(max = 60) String optionId, @Size(max = 2000) String answer) {
    }

    record OptionResponse(String id, String label, String detail) {
    }

    record ClarificationResponse(String id, String question, String detail,
                                 Map<String, Object> subject, List<OptionResponse> options,
                                 boolean allowsAssumption, String status,
                                 String answer, String createdAt, String answeredAt) {
    }

    @GetMapping
    List<ClarificationResponse> list(@AuthenticationPrincipal DroviPrincipal caller,
                                     @PathVariable UUID projectId) {
        return clarifications.forProject(caller.accountId(), projectId).stream()
                .map(ClarificationController::toResponse)
                .toList();
    }

    @PostMapping("/{clarificationId}/answer")
    ClarificationResponse answer(@AuthenticationPrincipal DroviPrincipal caller,
                                 @PathVariable UUID projectId,
                                 @PathVariable UUID clarificationId,
                                 @Valid @RequestBody AnswerRequest request) {
        return toResponse(clarifications.answer(
                caller.accountId(), clarificationId, request.optionId(), request.answer()));
    }

    /**
     * "You decide." A real answer rather than a way of skipping the question — this is a mock,
     * and for most doubts a plausible assumption beats a blocked generation. What was assumed is
     * recorded, so it can be found and changed later.
     */
    @PostMapping("/{clarificationId}/assume")
    ClarificationResponse assume(@AuthenticationPrincipal DroviPrincipal caller,
                                 @PathVariable UUID projectId,
                                 @PathVariable UUID clarificationId) {
        return toResponse(clarifications.assume(caller.accountId(), clarificationId));
    }

    private static ClarificationResponse toResponse(Clarification doubt) {
        return new ClarificationResponse(
                doubt.id().toString(), doubt.question(), doubt.detail(), doubt.subject(),
                doubt.options().stream()
                        .map(option -> new OptionResponse(option.id(), option.label(), option.detail()))
                        .toList(),
                doubt.allowsAssumption(), doubt.status().name(), doubt.answer(),
                doubt.createdAt() == null ? null : doubt.createdAt().toString(),
                doubt.answeredAt() == null ? null : doubt.answeredAt().toString());
    }
}
