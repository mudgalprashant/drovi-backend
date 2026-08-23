package com.pm.drovi_backend.web;

import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.GenerationService;
import com.pm.drovi_backend.identity.DroviPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Describe a product; watch a sandbox get built.
 *
 * <p>The whole of Phase 3 behind two routes. Everything the pipeline does — research, spec,
 * seed, and the chaining between them — happens after this returns, because a generation takes
 * minutes and a request that waited for it would hold a connection for all of them.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/generations")
@RequiredArgsConstructor
class GenerationController {

    private final GenerationService generations;

    /**
     * @param docs              documentation is <strong>recommended, never required</strong>
     *                          (ADR-0010). Supplying it produces a noticeably better sandbox
     * @param agentResearchOnly required when no docs are given, and it is a deliberate
     *                          friction: without it, "I did not paste anything" and "research
     *                          it for me" would be the same request, and every caller would
     *                          silently get the less accurate one
     */
    record StartRequest(@Size(max = 200) String product,
                        String docs,
                        @Size(max = 2000) String docsUrl,
                        Boolean agentResearchOnly) {

        /**
         * Boxed, and not a convenience. Jackson 3 refuses to map an absent field onto a
         * primitive {@code boolean} in a record — {@code FAIL_ON_NULL_FOR_PRIMITIVES} is on by
         * default — so a body that simply omits this would be rejected before any of our code
         * saw it. Absent is a legitimate way to say "no", and it means exactly what a false
         * would: no opt-in given.
         */
        boolean researchWithoutDocs() {
            return Boolean.TRUE.equals(agentResearchOnly);
        }
    }

    record StartedResponse(String jobId, String status, String message) {
    }

    record JobResponse(String id, String kind, String status, int attempt,
                       String errorCode, String errorMessage,
                       String createdAt, String finishedAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    StartedResponse start(@AuthenticationPrincipal DroviPrincipal caller,
                          @PathVariable UUID projectId,
                          @Valid @RequestBody StartRequest request) {
        GenerationJob job = generations.start(caller.accountId(), projectId,
                request.product(), request.docs(), request.docsUrl(), request.researchWithoutDocs());
        // 202, not 201: nothing is built yet. The console polls the history below, and the
        // project's own status is what says whether the sandbox is worth calling.
        return new StartedResponse(job.id().toString(), job.status().name(),
                "Generating. This takes a few minutes.");
    }

    @GetMapping
    List<JobResponse> history(@AuthenticationPrincipal DroviPrincipal caller,
                              @PathVariable UUID projectId) {
        return generations.history(caller.accountId(), projectId).stream()
                .map(job -> new JobResponse(
                        job.id().toString(), job.kind().name(), job.status().name(), job.attempt(),
                        job.errorCode(), job.errorMessage(),
                        job.createdAt() == null ? null : job.createdAt().toString(),
                        job.finishedAt() == null ? null : job.finishedAt().toString()))
                .toList();
    }
}
