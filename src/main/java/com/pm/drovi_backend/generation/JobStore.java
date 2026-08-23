package com.pm.drovi_backend.generation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Every read and write of {@code generation_job}, and nothing else.
 *
 * <p>All of it is `JdbcTemplate`, and every method is a <em>short</em> transaction. That
 * shape is forced by the work it supports: a job takes minutes, and a transaction may not be
 * open while it runs. The runner therefore claims in one transaction, calls the model in
 * none, and records the outcome in another.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobStore {

    private static final String COLUMNS =
            "id, account_id, project_id, thread_id, kind, status, prompt, attempt, input";

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private final RowMapper<GenerationJob> job = (ResultSet rs, int row) -> new GenerationJob(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("thread_id", UUID.class),
            JobKind.valueOf(rs.getString("kind")),
            JobStatus.valueOf(rs.getString("status")),
            rs.getString("prompt"),
            rs.getInt("attempt"),
            readInput(rs.getString("input")));

    private Map<String, Object> readInput(String json) {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, JSON_OBJECT);
    }

    @Transactional
    public GenerationJob enqueue(UUID accountId, UUID projectId, UUID threadId, JobKind kind, String prompt) {
        return enqueue(accountId, projectId, threadId, kind, prompt, Map.of());
    }

    @Transactional
    public GenerationJob enqueue(UUID accountId, UUID projectId, UUID threadId, JobKind kind,
                                 String prompt, Map<String, Object> input) {
        GenerationJob enqueued = jdbc.queryForObject("""
                INSERT INTO generation_job (account_id, project_id, thread_id, kind, prompt, input)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                RETURNING %s
                """.formatted(COLUMNS), job, accountId, projectId, threadId, kind.name(), prompt,
                mapper.writeValueAsString(input == null ? Map.of() : input));
        log.info("job.enqueued jobId={} kind={} accountId={}", enqueued.id(), kind, accountId);
        return enqueued;
    }

    /**
     * Take the oldest queued job <em>of a kind that can actually be run</em>, atomically.
     *
     * <p>The kind filter is not an optimisation. Without it, one queued job of a kind with no
     * handler sits at the head of the queue and is re-claimed on every tick forever, and
     * nothing behind it ever runs — a `SPEC` job nobody can serve yet would block every
     * `RESEARCH` job that works fine. Filtering in the query means an unrunnable kind simply
     * waits, harming nothing, until the day its handler lands.
     *
     * <p>The subquery takes {@code FOR UPDATE SKIP LOCKED} rather than relying on the
     * conditional {@code WHERE status = 'QUEUED'} alone. Both prevent two runners from
     * <em>claiming</em> the same row, but the plain conditional update makes the loser block
     * on the winner's row lock and then match nothing — so under contention a second runner
     * waits for a write it is guaranteed not to win. {@code SKIP LOCKED} lets it step over
     * that row and take the next one instead, which is what a queue wants.
     *
     * <p>The status predicate is still there and is still load-bearing: it is what makes a
     * claim impossible on a job somebody else already moved.
     *
     * <p>Incrementing {@code attempt} here — at claim, not at failure — is what makes a
     * runner that dies mid-job cost an attempt. If it were incremented on failure, a job that
     * kills its runner every time would be retried forever.
     *
     * @param runnableKinds the kinds a handler exists for. Empty means claim nothing.
     */
    @Transactional
    public Optional<GenerationJob> claimNext(Set<JobKind> runnableKinds) {
        if (runnableKinds.isEmpty()) {
            return Optional.empty();
        }
        // Placeholders are generated from the enum set's SIZE; the values themselves are still
        // bound as parameters. Nothing here is built from anything a caller supplies.
        String placeholders = String.join(",", Collections.nCopies(runnableKinds.size(), "?"));
        Object[] kinds = runnableKinds.stream().map(Enum::name).toArray();

        return jdbc.query("""
                UPDATE generation_job
                   SET status = 'RUNNING',
                       started_at = now(),
                       attempt = attempt + 1,
                       updated_at = now()
                 WHERE id = (SELECT id
                               FROM generation_job
                              WHERE status = 'QUEUED'
                                AND kind IN (%s)
                              ORDER BY created_at
                              LIMIT 1
                              FOR UPDATE SKIP LOCKED)
                   AND status = 'QUEUED'
                RETURNING %s
                """.formatted(placeholders, COLUMNS),
                rs -> rs.next() ? Optional.of(job.mapRow(rs, 1)) : Optional.<GenerationJob>empty(),
                kinds);
    }

    @Transactional
    public void succeed(UUID jobId, String resultJson) {
        succeed(jobId, resultJson, List.of());
    }

    /**
     * Mark this job succeeded and enqueue what follows it, in <strong>one transaction</strong>.
     *
     * <p>Split across two, the pipeline gets to break in both directions: a successor enqueued
     * against a predecessor that was never recorded as succeeded, or a job marked succeeded
     * with nothing following it and a generation that simply stops halfway with no error.
     * Neither is detectable afterwards without reconstructing what should have happened.
     *
     * @param next successors, already decided. They inherit this job's account, project and
     *             thread — a chain that could change owner mid-flight would be a way to write
     *             into somebody else's project
     */
    @Transactional
    public void succeed(UUID jobId, String resultJson, List<NewJob> next) {
        jdbc.update("""
                UPDATE generation_job
                   SET status = 'SUCCEEDED', result = ?::jsonb, error_code = NULL,
                       error_message = NULL, finished_at = now(), updated_at = now()
                 WHERE id = ?
                """, resultJson, jobId);

        for (NewJob successor : next) {
            jdbc.update("""
                    INSERT INTO generation_job (account_id, project_id, thread_id, kind, prompt, input)
                    SELECT account_id, project_id, thread_id, ?, ?, ?::jsonb
                      FROM generation_job WHERE id = ?
                    """, successor.kind().name(), successor.prompt(),
                    mapper.writeValueAsString(successor.input()), jobId);
        }

        log.info("job.succeeded jobId={} enqueued={}", jobId, next.size());
    }

    /**
     * Whether anything is still outstanding for a project — which is how the last step of a
     * generation recognises itself. A SEED job cannot know it is the last one; it can only know
     * that nothing else is left.
     */
    @Transactional(readOnly = true)
    public boolean hasUnfinishedJobs(UUID projectId, UUID excludingJobId) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM generation_job
                                WHERE project_id = ?
                                  AND id <> ?
                                  AND status IN ('QUEUED','RUNNING'))
                """, Boolean.class, projectId, excludingJobId);
    }

    /**
     * Terminal. {@code errorMessage} is <em>ours</em> — the column's comment says so and it
     * means it. An upstream provider's text changes without notice and routinely echoes the
     * prompt back, and this field is on its way to a user's screen.
     */
    @Transactional
    public void fail(UUID jobId, String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE generation_job
                   SET status = 'FAILED', error_code = ?, error_message = ?,
                       finished_at = now(), updated_at = now()
                 WHERE id = ?
                """, errorCode, errorMessage, jobId);
        log.warn("job.failed jobId={} errorCode={}", jobId, errorCode);
    }

    /**
     * Back to {@code QUEUED} for another attempt. {@code finished_at} stays null — the job has
     * not finished, and a timestamp saying otherwise would make every "how long do jobs take"
     * query wrong.
     */
    @Transactional
    public void requeue(UUID jobId, String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE generation_job
                   SET status = 'QUEUED', error_code = ?, error_message = ?, updated_at = now()
                 WHERE id = ?
                """, errorCode, errorMessage, jobId);
    }

    /**
     * Back to {@code QUEUED} <em>and</em> give the attempt back.
     *
     * <p>For the outcomes that say nothing about the job: the kill switch is off, no provider
     * is configured, no handler exists for its kind. Charging an attempt for those would let
     * a weekend with the kill switch off quietly exhaust every queued job's retries, and the
     * jobs would be found FAILED on Monday for a reason that had nothing to do with them.
     */
    @Transactional
    public void requeueWithoutPenalty(UUID jobId, String errorCode, String errorMessage) {
        jdbc.update("""
                UPDATE generation_job
                   SET status = 'QUEUED', attempt = greatest(attempt - 1, 0),
                       error_code = ?, error_message = ?, updated_at = now()
                 WHERE id = ?
                """, errorCode, errorMessage, jobId);
    }

    /**
     * A finished job's result, for the step that consumes it.
     *
     * <p>Restricted to {@code SUCCEEDED} on purpose: the column is also written on the way to
     * a retry, and building a spec on the output of a job that then failed would be building
     * on something nobody accepted.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> findResult(UUID jobId) {
        return Optional.ofNullable(jdbc.query(
                "SELECT result::text FROM generation_job WHERE id = ? AND status = 'SUCCEEDED'",
                rs -> rs.next() ? rs.getString(1) : null, jobId))
                .map(this::readInput);
    }

    @Transactional(readOnly = true)
    public Optional<GenerationJob> find(UUID jobId) {
        return jdbc.query("""
                SELECT %s FROM generation_job WHERE id = ?
                """.formatted(COLUMNS),
                rs -> rs.next() ? Optional.of(job.mapRow(rs, 1)) : Optional.<GenerationJob>empty(), jobId);
    }
}
