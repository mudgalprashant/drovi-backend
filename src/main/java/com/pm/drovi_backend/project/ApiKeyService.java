package com.pm.drovi_backend.project;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.Secrets;
import com.pm.drovi_backend.domain.ProjectApiKey;
import com.pm.drovi_backend.repo.ProjectApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keys the sandbox demands of its own callers.
 *
 * <p>A key is returned exactly once, at creation. Only its hash and a display prefix are
 * stored, so it cannot be shown again — which is why the console has to warn before that
 * dialog is dismissed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private static final int KEY_BYTES = 24;
    /** Recognisable at a glance in a log or a support ticket, and obviously a test key. */
    private static final String PREFIX = "sk_sbx_";
    private static final int DISPLAY_PREFIX_LENGTH = PREFIX.length() + 6;

    private final ProjectApiKeyRepository keys;
    private final ProjectService projects;

    /** @return the raw key — the only time it exists outside the caller's hands */
    @Transactional
    public Issued issue(UUID accountId, UUID projectId, String name) {
        projects.require(accountId, projectId);

        String raw = PREFIX + Secrets.randomToken(KEY_BYTES);
        ProjectApiKey key = keys.save(ProjectApiKey.issue(
                projectId,
                name == null || name.isBlank() ? "Default key" : name,
                raw.substring(0, DISPLAY_PREFIX_LENGTH),
                Secrets.sha256Hex(raw)));

        // The key itself is never logged. Its id and prefix are enough to trace it.
        log.info("apikey.issued keyId={} projectId={} prefix={}",
                key.getId(), projectId, key.getKeyPrefix());
        return new Issued(key, raw);
    }

    public record Issued(ProjectApiKey key, String rawKey) {
    }

    @Transactional(readOnly = true)
    public List<ProjectApiKey> list(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);
        return keys.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Revoked, never deleted — {@code mock_request_log} rows point at this key, and the
     * inspector should still be able to say which key made a call last week.
     */
    @Transactional
    public void revoke(UUID accountId, UUID projectId, UUID keyId) {
        projects.require(accountId, projectId);
        ProjectApiKey key = keys.findByIdAndProjectId(keyId, projectId)
                .orElseThrow(() -> DroviException.notFound("No such API key."));
        key.revoke(Instant.now());
        log.info("apikey.revoked keyId={} projectId={}", keyId, projectId);
    }
}
