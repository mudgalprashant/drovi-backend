package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.domain.SandboxCollection;
import com.pm.drovi_backend.domain.SandboxRecord;
import com.pm.drovi_backend.generation.TerminalJobException;
import com.pm.drovi_backend.project.SandboxDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a validated {@link RevisePlan}, all of it or none of it.
 *
 * <h2>Where the scoping actually happens</h2>
 *
 * Every collection code in the plan is resolved by {@link SandboxDataService#requireCollection}
 * with the caller's <em>account and project</em>. A code naming another tenant's collection does
 * not resolve — it is not refused by a check that could be forgotten, it simply does not exist
 * from here. That, and the fact that a {@link RevisePlan} has no vocabulary for anything but
 * records in a collection, is what makes the model's output safe to run.
 *
 * <p>Every write goes through the console's own service for the same reason SPEC's does: quota,
 * key handling and the sandbox's own PATCH semantics already live there, and a second write path
 * is one that eventually stops checking something.
 *
 * <h2>One transaction</h2>
 *
 * "Make five cards blocked and add two customers" that half-happens is worse than one that
 * fails: the user cannot tell what state their sandbox is in without inspecting it. The sandbox
 * keeps serving throughout — a reader sees the state before or the state after, never a
 * half-applied change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviseWriter {

    private final SandboxDataService data;

    /** @return what was done, per collection, for the job's result and the user's summary */
    @Transactional
    public Map<String, Object> apply(UUID accountId, UUID projectId, RevisePlan plan, int maxRecords) {
        Map<String, UUID> collectionIds = resolve(accountId, projectId, plan);
        Map<String, Integer> updated = new HashMap<>();
        Map<String, Integer> created = new HashMap<>();
        Map<String, Integer> deleted = new HashMap<>();
        int touched = 0;

        for (RevisePlan.Change change : plan.changes()) {
            UUID collectionId = collectionIds.get(change.collection());
            int limit = change.limit() == null ? maxRecords : Math.clamp(change.limit(), 1, maxRecords);

            switch (change.operation()) {
                case CREATE -> {
                    data.createRecords(accountId, projectId, collectionId, change.records());
                    created.merge(change.collection(), change.records().size(), Integer::sum);
                    touched += change.records().size();
                }
                case UPDATE -> {
                    List<String> keys = targets(accountId, projectId, collectionId, change, limit);
                    for (String key : keys) {
                        data.updateRecord(accountId, projectId, collectionId, key, change.set());
                    }
                    updated.merge(change.collection(), keys.size(), Integer::sum);
                    touched += keys.size();
                }
                case DELETE -> {
                    List<String> keys = targets(accountId, projectId, collectionId, change, limit);
                    for (String key : keys) {
                        data.deleteRecord(accountId, projectId, collectionId, key);
                    }
                    deleted.merge(change.collection(), keys.size(), Integer::sum);
                    touched += keys.size();
                }
            }

            if (touched > maxRecords) {
                // Checked as we go rather than only up front: a match can turn out to select far
                // more than the plan implied, and the transaction rolls back cleanly here.
                throw new TerminalJobException("REVISE_TOO_LARGE",
                        "That change touches more than %d records. Narrow it down.".formatted(maxRecords));
            }
        }

        log.info("revise.applied projectId={} updated={} created={} deleted={}",
                projectId, updated, created, deleted);
        return Map.of("summary", plan.summary(), "updated", updated,
                "created", created, "deleted", deleted, "recordsTouched", touched);
    }

    /**
     * Resolves every code up front, so a plan naming a collection that does not exist fails
     * before any part of it has been applied.
     */
    private Map<String, UUID> resolve(UUID accountId, UUID projectId, RevisePlan plan) {
        Map<String, UUID> byCode = new HashMap<>();
        List<SandboxCollection> collections = data.listCollections(accountId, projectId);
        for (RevisePlan.Change change : plan.changes()) {
            if (byCode.containsKey(change.collection())) {
                continue;
            }
            UUID id = collections.stream()
                    .filter(collection -> collection.getCode().equals(change.collection()))
                    .map(SandboxCollection::getId)
                    .findFirst()
                    .orElseThrow(() -> new TerminalJobException("REVISE_UNKNOWN_COLLECTION",
                            "This sandbox has no '%s' to change.".formatted(change.collection())));
            byCode.put(change.collection(), id);
        }
        return byCode;
    }

    /**
     * Explicit keys win over a match. When the model knows exactly which records it means, that
     * is more predictable than re-running a filter whose results may have moved.
     */
    private List<String> targets(UUID accountId, UUID projectId, UUID collectionId,
                                 RevisePlan.Change change, int limit) {
        if (!change.recordKeys().isEmpty()) {
            return change.recordKeys().stream().limit(limit).toList();
        }
        // One more than allowed, so an over-broad match is DETECTED rather than truncated. A
        // match that quietly selects the first 200 of 5,000 records is the same silent partial
        // application, arrived at by a different route.
        boolean userSetTheLimit = change.limit() != null;
        int fetch = userSetTheLimit ? limit : limit + 1;
        List<SandboxRecord> matched =
                data.findMatching(accountId, projectId, collectionId, change.match(), fetch);
        if (!userSetTheLimit && matched.size() > limit) {
            throw new TerminalJobException("REVISE_TOO_LARGE",
                    "That matches more than %d records. Narrow it down, or say how many you want."
                            .formatted(limit));
        }

        List<String> keys = new ArrayList<>();
        for (SandboxRecord record : matched) {
            keys.add(record.getRecordKey());
        }
        return keys;
    }
}
