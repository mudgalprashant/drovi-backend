package com.pm.drovi_backend.project;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.common.Secrets;
import com.pm.drovi_backend.domain.SandboxProject;
import com.pm.drovi_backend.identity.AccountService;
import com.pm.drovi_backend.identity.EntitlementService;
import com.pm.drovi_backend.repo.SandboxProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sandbox projects: create, read, update, archive.
 *
 * <p>Every method takes the caller's account id and every lookup is scoped by it in the
 * query. That is deliberate and worth preserving: loading by id and comparing owners
 * afterwards works right up until someone forgets the comparison, and the bug it produces
 * is one user reading another's sandbox.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    /** 24 bytes ≈ 192 bits. With auth_mode NONE this is the only guard on a sandbox. */
    private static final int PROJECT_KEY_BYTES = 24;
    private static final int MAX_KEY_ATTEMPTS = 5;

    private final SandboxProjectRepository projects;
    private final AccountService accounts;
    private final EntitlementService entitlements;

    @Transactional
    public SandboxProject create(UUID accountId, String name, String sourceProduct,
                                 String sourceDocsUrl, SandboxProject.AuthMode authMode) {
        int maxProjects = entitlements
                .forPlan(accounts.require(accountId).getPlanCode())
                .maxProjects();
        long existing = projects.countByAccountIdAndArchivedAtIsNull(accountId);
        if (existing >= maxProjects) {
            // 507 rather than 403: nothing is wrong with the request or the caller's
            // permissions — they are simply full, and the fix is a plan change or an
            // archive. Saying "forbidden" would send them looking for the wrong thing.
            throw new DroviException(ErrorCode.QUOTA_EXCEEDED,
                    "This plan allows %d projects; you have %d. Archive one or upgrade."
                            .formatted(maxProjects, existing));
        }

        SandboxProject project = SandboxProject.create(
                accountId, uniqueProjectKey(), name, sourceProduct, sourceDocsUrl,
                authMode == null ? SandboxProject.AuthMode.BEARER : authMode);
        SandboxProject saved = projects.save(project);
        log.info("project.created projectId={} accountId={}", saved.getId(), accountId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SandboxProject> list(UUID accountId) {
        return projects.findByAccountIdAndArchivedAtIsNullOrderByCreatedAtDesc(accountId);
    }

    /**
     * @throws DroviException NOT_FOUND when the project does not exist <em>or</em> is not
     *         the caller's. One response for both, so the API cannot be used to discover
     *         which project ids exist.
     */
    @Transactional(readOnly = true)
    public SandboxProject require(UUID accountId, UUID projectId) {
        return projects.findByIdAndAccountId(projectId, accountId)
                .orElseThrow(() -> DroviException.notFound("No such project."));
    }

    @Transactional
    public SandboxProject update(UUID accountId, UUID projectId, String name,
                                 SandboxProject.AuthMode authMode, String authHeaderName,
                                 Integer latencyMs) {
        SandboxProject project = require(accountId, projectId);
        project.update(name, authMode, authHeaderName, latencyMs);
        return project;
    }

    @Transactional
    public void archive(UUID accountId, UUID projectId) {
        SandboxProject project = require(accountId, projectId);
        project.archive(Instant.now());
        log.info("project.archived projectId={}", projectId);
    }

    /**
     * Retries on collision rather than trusting randomness blindly. At 192 bits a collision
     * is not going to happen — but a unique-constraint violation surfacing as a 500 to the
     * user who happened to hit it would be a poor way to find that out.
     */
    private String uniqueProjectKey() {
        for (int attempt = 0; attempt < MAX_KEY_ATTEMPTS; attempt++) {
            String candidate = Secrets.randomToken(PROJECT_KEY_BYTES);
            if (!projects.existsByProjectKey(candidate)) {
                return candidate;
            }
        }
        throw new DroviException(ErrorCode.INTERNAL, "Could not allocate a project key.");
    }
}
