package com.pm.drovi_backend.generation;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.domain.SandboxProject;
import com.pm.drovi_backend.project.ApiSpecService;
import com.pm.drovi_backend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Starting a generation, and watching one.
 *
 * <p>The console's way in. It does two things the pipeline deliberately cannot do for itself:
 * it works against a project the caller already owns, and it refuses obviously doomed requests
 * <em>before</em> the first model call rather than thirty seconds and one bill later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationService {

    /** One row of a project's generation history, as the console shows it. */
    public record JobView(UUID id, JobKind kind, JobStatus status, int attempt,
                          String errorCode, String errorMessage,
                          Instant createdAt, Instant finishedAt) {
    }

    private final JobStore jobs;
    private final ProjectService projects;
    private final ApiSpecService spec;
    private final JdbcTemplate jdbc;

    /**
     * Describe a product; get a job whose chain ends in a sandbox.
     *
     * @param docs              documentation the user pasted, or null
     * @param agentResearchOnly their explicit acceptance that, without docs, this is built from
     *                          the model's own knowledge of the product (ADR-0010)
     */
    @Transactional
    public GenerationJob start(UUID accountId, UUID projectId, String product,
                               String docs, String docsUrl, boolean agentResearchOnly) {
        SandboxProject project = projects.require(accountId, projectId);
        requireNothingAlreadyRunning(projectId);
        requireEmptyProject(accountId, projectId);

        Map<String, Object> input = new HashMap<>();
        input.put("product", product != null && !product.isBlank() ? product : project.getSourceProduct());
        input.put("agentResearchOnly", agentResearchOnly);
        if (docs != null && !docs.isBlank()) {
            input.put("docs", docs);
        }
        if (docsUrl != null && !docsUrl.isBlank()) {
            input.put("docsUrl", docsUrl);
        }

        // The sandbox stops serving until the chain finishes, so a project's routes and its
        // data appear together rather than one endpoint at a time.
        projects.markGenerating(accountId, projectId);
        GenerationJob job = jobs.enqueue(accountId, projectId, null, JobKind.RESEARCH,
                String.valueOf(input.get("product")), input);
        log.info("generation.started jobId={} projectId={} withDocs={}",
                job.id(), projectId, input.containsKey("docs"));
        return job;
    }

    /**
     * SPEC refuses a non-empty project too, and that check is the real one. This is here for
     * cost: without it a user learns their project is already built only after RESEARCH has
     * been paid for.
     */
    private void requireEmptyProject(UUID accountId, UUID projectId) {
        if (!spec.listEndpoints(accountId, projectId).isEmpty()) {
            throw new DroviException(ErrorCode.CONFLICT,
                    "This project already has endpoints. Generate into a new project instead.");
        }
    }

    /**
     * Two generations into one project would race to write the same structure, and the loser
     * would fail on a duplicate route having already spent its research.
     */
    private void requireNothingAlreadyRunning(UUID projectId) {
        if (jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM generation_job
                                WHERE project_id = ? AND status IN ('QUEUED','RUNNING'))
                """, Boolean.class, projectId)) {
            throw new DroviException(ErrorCode.CONFLICT,
                    "A generation is already running for this project.");
        }
    }

    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** Newest first — a console shows the current attempt, and history below it. */
    @Transactional(readOnly = true)
    public List<JobView> history(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);
        return jdbc.query("""
                SELECT id, kind, status, attempt, error_code, error_message, created_at, finished_at
                  FROM generation_job
                 WHERE project_id = ?
                 ORDER BY created_at DESC, kind
                """,
                (rs, row) -> new JobView(
                        rs.getObject("id", UUID.class),
                        JobKind.valueOf(rs.getString("kind")),
                        JobStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt"),
                        rs.getString("error_code"),
                        rs.getString("error_message"),
                        // OffsetDateTime, not Instant: PgJDBC refuses
                        // getObject(col, Instant.class) on a timestamptz with "conversion to
                        // class java.time.Instant from timestamptz not supported". The offset
                        // is UTC either way, so nothing is lost by going through it.
                        instantOf(rs.getObject("created_at", OffsetDateTime.class)),
                        instantOf(rs.getObject("finished_at", OffsetDateTime.class))),
                projectId);
    }
}
