package com.pm.drovi_backend.generation;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.config.AppConfigService;
import com.pm.drovi_backend.domain.SandboxProject;
import com.pm.drovi_backend.generation.clarify.ClarificationService;
import com.pm.drovi_backend.generation.pipeline.SpecImporter;
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
import java.util.Optional;
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

    /**
     * How much longer there is to wait, and whether the wait is on us or on the user.
     *
     * @param waitingForYou when true there is no estimate, because the clock is not running —
     *                      generation has stopped on a question only the user can answer.
     *                      Counting down to nothing is worse than saying nothing
     */
    public record Progress(boolean waitingForYou, int openQuestions, int stepsRemaining,
                           Integer estimatedSeconds) {
    }

    /** One row of a project's generation history, as the console shows it. */
    public record JobView(UUID id, JobKind kind, JobStatus status, int attempt,
                          String errorCode, String errorMessage,
                          Instant createdAt, Instant finishedAt) {
    }

    private static final int SECONDS_PER_STEP_DEFAULT = 45;
    private static final int EXPECTED_COLLECTIONS_DEFAULT = 3;

    private final JobStore jobs;
    private final ProjectService projects;
    private final ApiSpecService spec;
    private final ClarificationService clarifications;
    private final SpecImporter specImporter;
    private final AppConfigService config;
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

        // A recognised OpenAPI document or Postman collection skips RESEARCH entirely and starts
        // at SPEC, which then needs no model call either. The specification is not a hint about
        // the API — it is the API, and researching it would produce something less accurate at a
        // cost. Unrecognised input falls through to the normal path, costing nothing.
        // Asked as "will reading this produce a sandbox", not "does this look like a spec".
        // An OpenAPI file with an empty paths object is unmistakably OpenAPI and describes
        // nothing; routing it here would fail the user's generation instead of researching.
        Optional<SpecImporter.Format> importable = specImporter.importableAs(docs);
        if (importable.isPresent()) {
            GenerationJob imported = jobs.enqueue(accountId, projectId, null, JobKind.SPEC,
                    "Import the supplied specification",
                    Map.of("spec", docs, "specFormat", importable.get().name()));
            log.info("generation.started.fromSpec jobId={} projectId={} format={}",
                    imported.id(), projectId, importable.get());
            return imported;
        }

        GenerationJob job = jobs.enqueue(accountId, projectId, null, JobKind.RESEARCH,
                String.valueOf(input.get("product")), input);
        log.info("generation.started jobId={} projectId={} withDocs={}",
                job.id(), projectId, input.containsKey("docs"));
        return job;
    }

    /**
     * Change a sandbox that already exists.
     *
     * <p>Unlike a generation this does <strong>not</strong> touch the project's status. The
     * sandbox keeps serving while the revision runs — the change lands in one transaction, so a
     * caller sees before or after and never half of one, and taking a working sandbox offline
     * to adjust five records would be a poor trade.
     */
    @Transactional
    public GenerationJob revise(UUID accountId, UUID projectId, String instruction) {
        projects.require(accountId, projectId);
        requireNothingAlreadyRunning(projectId);
        if (spec.listEndpoints(accountId, projectId).isEmpty()) {
            throw new DroviException(ErrorCode.CONFLICT,
                    "This sandbox has nothing to change yet. Generate it first.");
        }

        GenerationJob job = jobs.enqueue(accountId, projectId, null, JobKind.REVISE,
                instruction, Map.of("instruction", instruction));
        log.info("revision.started jobId={} projectId={}", job.id(), projectId);
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
                    "Something is already running for this project. Wait for it to finish.");
        }
    }

    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * How much longer, in seconds.
     *
     * <p>Built from a configured per-step figure rather than measured timings, because there
     * are no real timings yet and a made-up average presented as data is worse than an honest
     * constant an operator can correct with one UPDATE.
     *
     * <p>Before the spec exists nobody knows how many collections there will be, so the seed
     * steps are assumed. After it they are counted — the estimate gets more truthful as the
     * generation proceeds, which is the right direction for it to move.
     */
    @Transactional(readOnly = true)
    public Progress progress(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);

        int open = (int) clarifications.forProject(accountId, projectId).stream()
                .filter(doubt -> doubt.isOpen())
                .count();
        if (open > 0) {
            return new Progress(true, open, 0, null);
        }

        int outstanding = jdbc.queryForObject("""
                SELECT count(*) FROM generation_job
                 WHERE project_id = ? AND status IN ('QUEUED','RUNNING')
                """, Integer.class, projectId);
        if (outstanding == 0) {
            return new Progress(false, 0, 0, 0);
        }

        boolean specDone = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM generation_job
                                WHERE project_id = ? AND kind = 'SPEC' AND status = 'SUCCEEDED')
                """, Boolean.class, projectId);
        // Before the spec, the seed jobs it will produce are not rows yet and have to be
        // assumed. After it, everything left is already queued.
        int stepsRemaining = specDone
                ? outstanding
                : outstanding + config.getInt("ai.generation.expected.collections", EXPECTED_COLLECTIONS_DEFAULT);

        return new Progress(false, 0, stepsRemaining,
                stepsRemaining * config.getInt("ai.job.estimated.seconds.per.step", SECONDS_PER_STEP_DEFAULT));
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
