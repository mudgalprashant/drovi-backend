package com.pm.drovi_backend.generation.clarify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A doubt the system had, and what it was told.
 *
 * <p>Raised when a request is ambiguous in a way the person asking cannot see — "give me a
 * blocked card" when three endpoints serve cards, or when a card has {@code status},
 * {@code state} and {@code blocked} all plausibly meaning it. Guessing produces a sandbox that
 * looks right and is wrong, which is the expensive kind: the user builds against it before
 * finding out.
 */
public record Clarification(UUID id,
                            UUID projectId,
                            UUID jobId,
                            String question,
                            String detail,
                            Map<String, Object> subject,
                            List<Option> options,
                            boolean allowsAssumption,
                            Status status,
                            String answer,
                            String answeredOption,
                            Instant createdAt,
                            Instant answeredAt) {

    public enum Status {
        /** Generation is waiting on this. */
        OPEN,
        /** The user chose. */
        ANSWERED,
        /**
         * The user said "you decide". What was decided is recorded in {@code answer} anyway —
         * an assumption nobody can look up later is indistinguishable from a bug.
         */
        ASSUMED
    }

    /**
     * A candidate answer, offered rather than demanded. A user shown three concrete options
     * answers in one click; a user shown a blank box does not answer at all.
     */
    public record Option(String id, String label, String detail) {
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    /** What the next generation step is told, so the same question is not asked twice. */
    public String asResolvedInstruction() {
        return switch (status) {
            case ANSWERED -> "%s → %s".formatted(question, answer);
            case ASSUMED -> "%s → the user asked us to decide; assume something plausible and be consistent."
                    .formatted(question);
            case OPEN -> question;
        };
    }
}
