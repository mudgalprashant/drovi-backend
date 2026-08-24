package com.pm.drovi_backend.web;

import com.pm.drovi_backend.config.DroviProperties;
import com.pm.drovi_backend.domain.ProjectApiKey;
import com.pm.drovi_backend.domain.SandboxProject;
import com.pm.drovi_backend.identity.DroviPrincipal;
import com.pm.drovi_backend.project.ApiKeyService;
import com.pm.drovi_backend.project.InspectorService;
import com.pm.drovi_backend.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sandbox projects and the API keys their callers use.
 *
 * <p>No endpoint here takes an account id. Ownership comes from the authenticated
 * principal and is applied inside the query, so there is no id in a path or body that could
 * be swapped for somebody else's.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
class ProjectController {

    private final ProjectService projects;
    private final ApiKeyService apiKeys;
    private final InspectorService inspector;
    private final DroviProperties properties;

    // --- requests ------------------------------------------------------------

    record CreateProjectRequest(@NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 120) String sourceProduct,
                                @Size(max = 500) String sourceDocsUrl,
                                SandboxProject.AuthMode authMode) {
    }

    /** Every field optional — PATCH is sparse, so absent means "leave it alone". */
    record UpdateProjectRequest(@Size(max = 120) String name,
                                SandboxProject.AuthMode authMode,
                                @Size(max = 80) String authHeaderName,
                                Integer latencyMs) {
    }

    record CreateKeyRequest(@Size(max = 80) String name) {
    }

    // --- responses -----------------------------------------------------------

    record ProjectResponse(String id,
                           String baseUrl,
                           String name,
                           String sourceProduct,
                           String sourceDocsUrl,
                           String status,
                           String authMode,
                           String authHeaderName,
                           int latencyMs,
                           Instant createdAt) {
    }

    record KeyResponse(String id, String name, String keyPrefix,
                       Instant lastUsedAt, Instant revokedAt, Instant createdAt) {
    }

    /**
     * The only response that ever carries {@code key}. Drovi stores a hash, so this value
     * cannot be produced again — the console must warn before it is dismissed.
     */
    record IssuedKeyResponse(String id, String name, String keyPrefix, String key,
                             Instant createdAt, String warning) {
    }

    // --- projects ------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse create(@AuthenticationPrincipal DroviPrincipal principal,
                           @Valid @RequestBody CreateProjectRequest request) {
        return toResponse(projects.create(principal.accountId(), request.name(),
                request.sourceProduct(), request.sourceDocsUrl(), request.authMode()));
    }

    @GetMapping
    List<ProjectResponse> list(@AuthenticationPrincipal DroviPrincipal principal) {
        return projects.list(principal.accountId()).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(@AuthenticationPrincipal DroviPrincipal principal,
                        @PathVariable UUID projectId) {
        return toResponse(projects.require(principal.accountId(), projectId));
    }

    @PatchMapping("/{projectId}")
    ProjectResponse update(@AuthenticationPrincipal DroviPrincipal principal,
                           @PathVariable UUID projectId,
                           @Valid @RequestBody UpdateProjectRequest request) {
        return toResponse(projects.update(principal.accountId(), projectId, request.name(),
                request.authMode(), request.authHeaderName(), request.latencyMs()));
    }

    /** Archives rather than deletes: the sandbox stops serving, the data survives. */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal DroviPrincipal principal, @PathVariable UUID projectId) {
        projects.archive(principal.accountId(), projectId);
    }

    // --- keys ----------------------------------------------------------------

    @PostMapping("/{projectId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedKeyResponse issueKey(@AuthenticationPrincipal DroviPrincipal principal,
                               @PathVariable UUID projectId,
                               @Valid @RequestBody(required = false) CreateKeyRequest request) {
        ApiKeyService.Issued issued = apiKeys.issue(principal.accountId(), projectId,
                request == null ? null : request.name());
        return new IssuedKeyResponse(
                issued.key().getId().toString(),
                issued.key().getName(),
                issued.key().getKeyPrefix(),
                issued.rawKey(),
                issued.key().getCreatedAt(),
                "Copy this key now. It is stored only as a hash and cannot be shown again.");
    }

    @GetMapping("/{projectId}/keys")
    List<KeyResponse> listKeys(@AuthenticationPrincipal DroviPrincipal principal,
                               @PathVariable UUID projectId) {
        return apiKeys.list(principal.accountId(), projectId).stream()
                .map(k -> new KeyResponse(k.getId().toString(), k.getName(), k.getKeyPrefix(),
                        k.getLastUsedAt(), k.getRevokedAt(), k.getCreatedAt()))
                .toList();
    }

    @DeleteMapping("/{projectId}/keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeKey(@AuthenticationPrincipal DroviPrincipal principal,
                   @PathVariable UUID projectId, @PathVariable UUID keyId) {
        apiKeys.revoke(principal.accountId(), projectId, keyId);
    }

    // --- inspector -----------------------------------------------------------

    /**
     * A tail of what the sandbox served. {@code unmatchedOnly=true} is the debugging view:
     * a call nothing matched usually means the spec has a path the real product does not.
     */
    @GetMapping("/{projectId}/requests")
    InspectorService.Page requests(@AuthenticationPrincipal DroviPrincipal principal,
                                   @PathVariable UUID projectId,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "false") boolean unmatchedOnly) {
        return inspector.tail(principal.accountId(), projectId, limit, before, unmatchedOnly);
    }

    private ProjectResponse toResponse(SandboxProject project) {
        return new ProjectResponse(
                project.getId().toString(),
                // The artifact the user actually came for: paste this over the production
                // base URL and nothing else in their code changes. It is built from the
                // project's own id, so the thing they see in the console and the thing they
                // paste into their code are the same identifier.
                properties.baseUrlFor(project.getId()),
                project.getName(),
                project.getSourceProduct(),
                project.getSourceDocsUrl(),
                project.getStatus().name(),
                project.getAuthMode().name(),
                project.getAuthHeaderName(),
                project.getLatencyMs(),
                project.getCreatedAt());
    }
}
