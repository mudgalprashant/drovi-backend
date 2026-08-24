package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.generation.RetryableJobException;
import com.pm.drovi_backend.generation.TerminalJobException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A set of changes to an existing sandbox's data, parsed and checked before any of it happens.
 *
 * <h2>The security shape</h2>
 *
 * This is the model's output acting on a user's data, which is the most dangerous thing in the
 * system — so the danger is removed structurally rather than by asking the model nicely.
 *
 * <p><strong>A change names a collection by its code, and nothing else.</strong> There is no
 * project id in this type, no account id, no table name, no SQL. {@link ReviseWriter} resolves
 * every code against the caller's own project, so a plan <em>cannot express</em> a write to
 * another tenant, to a plan, to a quota, or to {@code app_config}. Those are not forbidden;
 * they are unreachable.
 *
 * <p>The same reasoning is why this is a plan the platform applies rather than tools the model
 * calls. A tool loop can be talked into a call it should not make; a plan can only say the
 * things this record can hold.
 */
record RevisePlan(String summary, List<Change> changes) {

    /** What one change does. Three operations, because a fourth is a fourth thing to get wrong. */
    enum Operation { UPDATE, CREATE, DELETE }

    /**
     * @param match      jsonb containment — records whose data contains all of these fields
     * @param recordKeys explicit ids, when the model knows exactly which records it means
     * @param set        fields to merge into each matched record, for UPDATE
     * @param records    whole records to add, for CREATE
     */
    record Change(String collection, Operation operation,
                  Map<String, Object> match, List<String> recordKeys,
                  Map<String, Object> set, List<Map<String, Object>> records,
                  Integer limit) {

        boolean isTargeted() {
            return (recordKeys != null && !recordKeys.isEmpty()) || (match != null && !match.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    static RevisePlan parse(Map<String, Object> raw, int maxRecords) {
        List<Change> changes = new ArrayList<>();
        for (Object item : raw.get("changes") instanceof List<?> list ? list : List.<Object>of()) {
            if (!(item instanceof Map<?, ?>)) {
                throw new RetryableJobException("a change is not an object");
            }
            Map<String, Object> map = (Map<String, Object>) item;
            String collection = map.get("collection") instanceof String code && !code.isBlank()
                    ? code.trim() : null;
            if (collection == null) {
                throw new RetryableJobException("a change does not say which collection it affects");
            }
            changes.add(new Change(
                    collection,
                    operation(map.get("operation")),
                    map.get("match") instanceof Map<?, ?> match ? (Map<String, Object>) match : Map.of(),
                    map.get("recordKeys") instanceof List<?> keys
                            ? keys.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                            : List.of(),
                    map.get("set") instanceof Map<?, ?> set ? (Map<String, Object>) set : Map.of(),
                    map.get("records") instanceof List<?> rows
                            ? rows.stream().filter(Map.class::isInstance)
                                    .map(row -> (Map<String, Object>) row).toList()
                            : List.of(),
                    map.get("limit") instanceof Number limit ? limit.intValue() : null));
        }

        RevisePlan plan = new RevisePlan(
                raw.get("summary") instanceof String summary ? summary : "Updated your sandbox.",
                changes);
        plan.validate(maxRecords);
        return plan;
    }

    private void validate(int maxRecords) {
        if (changes.isEmpty()) {
            // Not a retry — the model understood and had nothing to do, or the instruction did
            // not describe a data change. Another attempt reaches the same place.
            throw new TerminalJobException("REVISE_NO_CHANGES",
                    "That did not describe a change we could make to your sandbox's data.");
        }
        for (Change change : changes) {
            switch (change.operation()) {
                case UPDATE -> {
                    if (change.set().isEmpty()) {
                        throw new RetryableJobException("an UPDATE says nothing to set");
                    }
                    requireTargeted(change, "UPDATE");
                    requireWithinCeiling(change, maxRecords);
                }
                case DELETE -> {
                    requireTargeted(change, "DELETE");
                    requireWithinCeiling(change, maxRecords);
                }
                case CREATE -> {
                    if (change.records().isEmpty()) {
                        throw new RetryableJobException("a CREATE adds no records");
                    }
                    if (change.records().size() > maxRecords) {
                        throw new TerminalJobException("REVISE_TOO_LARGE",
                                "That would add %d records at once; the limit is %d."
                                        .formatted(change.records().size(), maxRecords));
                    }
                }
            }
        }
    }

    /**
     * An unqualified UPDATE or DELETE would rewrite or remove every record in a collection.
     * The project's own rule against unbounded writes exists for exactly this, and here the
     * instruction comes from a model reading a sentence — so "change the cards" must not be
     * allowed to mean "all of them" by omission.
     */
    private static void requireTargeted(Change change, String what) {
        if (!change.isTargeted()) {
            throw new TerminalJobException("REVISE_TOO_BROAD",
                    "A %s needs to say which records it affects. Try naming them, or the field that identifies them."
                            .formatted(what));
        }
    }

    /**
     * Refused rather than clamped. Silently applying one of the three changes a user asked for
     * is the "looks right and is wrong" failure again — they would have to count the records to
     * discover it. Saying so costs them a retry and nothing else.
     */
    private static void requireWithinCeiling(Change change, int maxRecords) {
        int asked = Math.max(change.recordKeys().size(),
                change.limit() == null ? 0 : change.limit());
        if (asked > maxRecords) {
            throw new TerminalJobException("REVISE_TOO_LARGE",
                    "That change touches %d records and the limit is %d. Narrow it down."
                            .formatted(asked, maxRecords));
        }
    }

    private static Operation operation(Object value) {
        if (!(value instanceof String name)) {
            throw new RetryableJobException("a change does not say what it does");
        }
        try {
            return Operation.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RetryableJobException("unknown change operation '%s'".formatted(name));
        }
    }
}
