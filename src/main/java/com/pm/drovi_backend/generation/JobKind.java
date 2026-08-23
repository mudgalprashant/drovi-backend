package com.pm.drovi_backend.generation;

/**
 * What a generation job does. Mirrors the {@code CHECK} constraint on
 * {@code generation_job.kind}.
 *
 * <p>Not the same list as {@code AiPurpose}, and the difference is the point: a job is a
 * unit of work that may make several model calls, and {@code TITLE} or {@code CHAT} are
 * calls nobody queues. Merging the two enums would force one of them to carry values the
 * other has no meaning for.
 */
public enum JobKind {

    /** Work out what the real product's API looks like. */
    RESEARCH,
    /** Turn research into API groups, endpoints and schemas. */
    SPEC,
    /** Generate the sandbox records those endpoints serve. */
    SEED,
    /** Apply a chat instruction to an existing sandbox. */
    REVISE
}
