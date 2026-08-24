package com.pm.drovi_backend.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * One turn in a conversation about a sandbox.
 *
 * <p>{@code seq} rather than {@code createdAt} orders these, and the schema says why: a
 * question and the answer that follows it can land inside the same millisecond, and a
 * transcript that shows them the wrong way round reads as nonsense.
 */
public record ChatMessage(UUID id, int seq, Role role, String content, Instant createdAt) {

    /** Mirrors the {@code CHECK} on {@code chat_message.role}. */
    public enum Role { USER, ASSISTANT, TOOL, SYSTEM }
}
