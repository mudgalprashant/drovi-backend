package com.pm.drovi_backend.ai;

/**
 * Why a model call is being made.
 *
 * <p>The names are the contract with two database columns, not a private enum: they are
 * the {@code CHECK} constraint on {@code ai_call.purpose}, and they are the suffix of the
 * {@code ai.model.<PURPOSE>} routing keys in {@code app_config}. Renaming one silently
 * un-routes it — the call still works, but it falls back to the default model and nobody
 * finds out until the bill.
 */
public enum AiPurpose {

    /** Working out what the real product's API actually looks like. */
    RESEARCH,
    /** Turning research into API groups, endpoints and schemas. */
    SPEC,
    /** Generating sandbox records. The highest-volume purpose. */
    SEED,
    /** Applying a chat instruction to an existing sandbox. */
    REVISE,
    /** A conversational turn that does not mutate the project. */
    CHAT,
    /** Naming a thread. */
    TITLE
}
