package com.pm.drovi_backend.generation.clarify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every read and write of {@code generation_clarification}, and nothing else. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClarificationStore {

    private static final String COLUMNS = """
            id, project_id, job_id, question, detail, subject, options, allows_assumption,
            status, answer, answered_option, created_at, answered_at""";

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<List<Clarification.Option>> OPTIONS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private final RowMapper<Clarification> row = (ResultSet rs, int i) -> new Clarification(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("job_id", UUID.class),
            rs.getString("question"),
            rs.getString("detail"),
            subjectOf(rs.getString("subject")),
            optionsOf(rs.getString("options")),
            rs.getBoolean("allows_assumption"),
            Clarification.Status.valueOf(rs.getString("status")),
            rs.getString("answer"),
            rs.getString("answered_option"),
            // OffsetDateTime, not Instant: PgJDBC will not convert a timestamptz to an Instant.
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("answered_at", OffsetDateTime.class)));

    private Map<String, Object> subjectOf(String json) {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, JSON_OBJECT);
    }

    private List<Clarification.Option> optionsOf(String json) {
        return json == null || json.isBlank() ? List.of() : mapper.readValue(json, OPTIONS);
    }

    @Transactional
    public UUID raise(UUID accountId, UUID projectId, UUID jobId, UUID threadId,
                      String question, String detail, Map<String, Object> subject,
                      List<Clarification.Option> options, boolean allowsAssumption) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO generation_clarification
                    (account_id, project_id, job_id, thread_id, question, detail,
                     subject, options, allows_assumption)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING id
                """, UUID.class,
                accountId, projectId, jobId, threadId, question, detail,
                mapper.writeValueAsString(subject == null ? Map.of() : subject),
                mapper.writeValueAsString(options == null ? List.of() : options),
                allowsAssumption);
        log.info("clarification.raised id={} projectId={} jobId={}", id, projectId, jobId);
        return id;
    }

    /** Everything ever asked about this project, newest first. Answered rows are kept. */
    @Transactional(readOnly = true)
    public List<Clarification> forProject(UUID projectId) {
        return jdbc.query("SELECT %s FROM generation_clarification WHERE project_id = ? ORDER BY created_at DESC"
                .formatted(COLUMNS), row, projectId);
    }

    @Transactional(readOnly = true)
    public List<Clarification> openFor(UUID projectId) {
        return jdbc.query("""
                SELECT %s FROM generation_clarification
                 WHERE project_id = ? AND status = 'OPEN' ORDER BY created_at
                """.formatted(COLUMNS), row, projectId);
    }

    /** Resolved ones, so the next step can be told what was already decided. */
    @Transactional(readOnly = true)
    public List<Clarification> resolvedFor(UUID projectId) {
        return jdbc.query("""
                SELECT %s FROM generation_clarification
                 WHERE project_id = ? AND status <> 'OPEN' ORDER BY created_at
                """.formatted(COLUMNS), row, projectId);
    }

    /**
     * Scoped by account as well as id, so another tenant's doubt is indistinguishable from one
     * that does not exist.
     */
    @Transactional(readOnly = true)
    public Optional<Clarification> find(UUID accountId, UUID clarificationId) {
        return jdbc.query("SELECT %s FROM generation_clarification WHERE id = ? AND account_id = ?"
                        .formatted(COLUMNS),
                rs -> rs.next() ? Optional.of(row.mapRow(rs, 1)) : Optional.<Clarification>empty(),
                clarificationId, accountId);
    }

    /**
     * @return true when this call is what closed it. The conditional {@code status = 'OPEN'}
     *         is what makes two clicks on the same answer resolve it once, which matters
     *         because resolving the last one is what restarts the generation
     */
    @Transactional
    public boolean resolve(UUID clarificationId, Clarification.Status status,
                           String answer, String answeredOption) {
        int updated = jdbc.update("""
                UPDATE generation_clarification
                   SET status = ?, answer = ?, answered_option = ?,
                       answered_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'OPEN'
                """, status.name(), answer, answeredOption, clarificationId);
        return updated == 1;
    }

    @Transactional(readOnly = true)
    public boolean hasOpen(UUID projectId) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM generation_clarification
                                WHERE project_id = ? AND status = 'OPEN')
                """, Boolean.class, projectId);
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
