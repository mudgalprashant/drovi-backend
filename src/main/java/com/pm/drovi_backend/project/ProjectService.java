package com.pm.drovi_backend.project;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
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
                accountId, name, sourceProduct, sourceDocsUrl,
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

    /**
     * The three transitions generation owns. Separate from {@link #update} because they are
     * not the user's to make: a client that could set its own project READY could publish a
     * half-generated sandbox, and one that could set it GENERATING could stop its own sandbox
     * serving for no reason.
     */
    /**
     * How this replica's in-character errors should look. Generation's to set, not the user's:
     * it is a property of the imitation, and a console field for hand-editing JSON is not the
     * shape of that.
     */
    @Transactional
    public void useErrorEnvelope(UUID accountId, UUID projectId, java.util.Map<String, Object> envelope) {
        require(accountId, projectId).useErrorEnvelope(envelope);
    }

    @Transactional
    public void markGenerating(UUID accountId, UUID projectId) {
        require(accountId, projectId).markGenerating();
        log.info("project.generating projectId={}", projectId);
    }

    @Transactional
    public void markReady(UUID accountId, UUID projectId) {
        require(accountId, projectId).markReady();
        log.info("project.ready projectId={}", projectId);
    }

    @Transactional
    public void markGenerationFailed(UUID accountId, UUID projectId) {
        require(accountId, projectId).markGenerationFailed();
        log.info("project.generation.failed projectId={}", projectId);
    }

    @Transactional
    public void archive(UUID accountId, UUID projectId) {
        SandboxProject project = require(accountId, projectId);
        project.archive(Instant.now());
        log.info("project.archived projectId={}", projectId);
    }

}
