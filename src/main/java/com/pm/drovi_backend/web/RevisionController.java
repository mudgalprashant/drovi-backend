package com.pm.drovi_backend.web;

import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.GenerationService;
import com.pm.drovi_backend.identity.DroviPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Changing a sandbox that already exists: <em>"make five customers' cards blocked"</em>.
 *
 * <p>Asynchronous like a generation, and for the same reason — it is a model call, which takes
 * seconds at best. The sandbox keeps serving throughout; the change lands in one transaction, so
 * a caller sees the state before or the state after and never half of one.
 *
 * <p>If the instruction is ambiguous, nothing is changed and questions appear under
 * {@code /clarifications}. Answering the last one re-runs the revision with the answers.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/revisions")
@RequiredArgsConstructor
class RevisionController {

    private final GenerationService generations;

    record ReviseRequest(@NotBlank @Size(max = 2000) String instruction) {
    }

    record RevisionStartedResponse(String jobId, String status, String message) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    RevisionStartedResponse revise(@AuthenticationPrincipal DroviPrincipal caller,
                                   @PathVariable UUID projectId,
                                   @Valid @RequestBody ReviseRequest request) {
        GenerationJob job = generations.revise(caller.accountId(), projectId, request.instruction());
        return new RevisionStartedResponse(job.id().toString(), job.status().name(),
                "Working on it. Check /clarifications if we need anything from you.");
    }
}
