package com.pm.drovi_backend.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every read and write of {@code chat_thread} and {@code chat_message}, and nothing else. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatStore {

    /** A conversation, and the project it is about. */
    public record Thread(UUID id, UUID accountId, UUID projectId, String title,
                         java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    private static final RowMapper<Thread> THREAD = (ResultSet rs, int i) -> new Thread(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getString("title"),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private static final RowMapper<ChatMessage> MESSAGE = (ResultSet rs, int i) -> new ChatMessage(
            rs.getObject("id", UUID.class),
            rs.getInt("seq"),
            ChatMessage.Role.valueOf(rs.getString("role")),
            rs.getString("content"),
            instant(rs.getObject("created_at", OffsetDateTime.class)));

    @Transactional
    public Thread createThread(UUID accountId, UUID projectId, String title) {
        Thread thread = jdbc.queryForObject("""
                INSERT INTO chat_thread (account_id, project_id, title)
                VALUES (?, ?, ?)
                RETURNING id, account_id, project_id, title, created_at, updated_at
                """, THREAD, accountId, projectId,
                title == null || title.isBlank() ? "New sandbox" : title.trim());
        log.info("chat.thread.created threadId={} projectId={}", thread.id(), projectId);
        return thread;
    }

    /**
     * Appends a message, taking the thread's row lock first.
     *
     * <p>{@code seq} is unique per thread, so two appends racing on
     * {@code max(seq) + 1} both compute the same number and one loses on the index. Locking the
     * thread makes them queue instead — which matters more than it sounds, because the racing
     * pair is usually a user's answer and the system's reply to it.
     *
     * <p>{@code REQUIRES_NEW}: a transcript entry describes something that happened, and it must
     * survive the failure of whatever it was describing. A generation that fails should still
     * show the message saying it was starting.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage append(UUID threadId, ChatMessage.Role role, String content) {
        jdbc.queryForObject("SELECT id FROM chat_thread WHERE id = ? FOR UPDATE", UUID.class, threadId);
        ChatMessage message = jdbc.queryForObject("""
                INSERT INTO chat_message (thread_id, seq, role, content)
                VALUES (?, (SELECT coalesce(max(seq), 0) + 1 FROM chat_message WHERE thread_id = ?), ?, ?)
                RETURNING id, seq, role, content, created_at
                """, MESSAGE, threadId, threadId, role.name(), content);
        jdbc.update("UPDATE chat_thread SET updated_at = now() WHERE id = ?", threadId);
        return message;
    }

    /** Scoped by account, so another tenant's thread is indistinguishable from a missing one. */
    @Transactional(readOnly = true)
    public Optional<Thread> find(UUID accountId, UUID threadId) {
        return jdbc.query("""
                SELECT id, account_id, project_id, title, created_at, updated_at
                  FROM chat_thread WHERE id = ? AND account_id = ?
                """, rs -> rs.next() ? Optional.of(THREAD.mapRow(rs, 1)) : Optional.<Thread>empty(),
                threadId, accountId);
    }

    /** The thread a project's events should be narrated into, if it has one. */
    @Transactional(readOnly = true)
    public Optional<UUID> newestThreadFor(UUID projectId) {
        return Optional.ofNullable(jdbc.query("""
                SELECT id FROM chat_thread
                 WHERE project_id = ? AND archived_at IS NULL
                 ORDER BY updated_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, projectId));
    }

    @Transactional(readOnly = true)
    public List<Thread> threadsFor(UUID accountId) {
        return jdbc.query("""
                SELECT id, account_id, project_id, title, created_at, updated_at
                  FROM chat_thread WHERE account_id = ? AND archived_at IS NULL
                 ORDER BY updated_at DESC
                """, THREAD, accountId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messages(UUID threadId) {
        return jdbc.query("""
                SELECT id, seq, role, content, created_at FROM chat_message
                 WHERE thread_id = ? ORDER BY seq
                """, MESSAGE, threadId);
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
