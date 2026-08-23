package com.pm.drovi_backend.runtime;

import com.pm.drovi_backend.domain.SandboxCollection;
import com.pm.drovi_backend.domain.SandboxRecord;
import com.pm.drovi_backend.repo.SandboxRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one way a record is written.
 *
 * <p>Two callers reach this: the mock runtime, when a sandbox serves a CREATE endpoint, and
 * the console API, when a user edits or seeds data. They must behave identically — the same
 * key extraction, the same quota accounting, the same duplicate handling. Two
 * implementations would drift, and the way that drift shows up is a write path that skips
 * the quota check and quietly fills the database.
 */
@Component
@RequiredArgsConstructor
public class RecordWriter {

    private final SandboxRecordRepository records;
    private final QuotaService quotas;
    private final ObjectMapper mapper;

    /** Thrown when the key already exists; callers render it in their own shape. */
    public static class DuplicateRecordException extends RuntimeException {
        public DuplicateRecordException(String message) {
            super(message);
        }
    }

    @Transactional
    public SandboxRecord write(UUID projectId, SandboxCollection collection, Map<String, Object> payload) {
        return writeAll(projectId, collection, List.of(payload)).getFirst();
    }

    /**
     * Quota is checked <b>once for the whole batch, before any insert</b>. Checking per row
     * would let a 10,000-row seed write 9,999 rows and then fail — leaving the project over
     * its limit and the user with a half-loaded collection.
     */
    @Transactional
    public List<SandboxRecord> writeAll(UUID projectId, SandboxCollection collection,
                                        List<Map<String, Object>> payloads) {
        List<Map<String, Object>> prepared = new ArrayList<>(payloads.size());
        long bytes = 0;
        for (Map<String, Object> payload : payloads) {
            Map<String, Object> data = withKey(collection, payload);
            prepared.add(data);
            bytes += estimateBytes(data);
        }

        quotas.requireCapacityFor(projectId, prepared.size(), bytes);

        List<SandboxRecord> written = new ArrayList<>(prepared.size());
        for (Map<String, Object> data : prepared) {
            String key = String.valueOf(data.get(collection.getKeyField()));
            if (records.findByCollectionIdAndRecordKey(collection.getId(), key).isPresent()) {
                throw new DuplicateRecordException(
                        "A %s with id %s already exists.".formatted(collection.getCode(), key));
            }
            written.add(records.save(
                    SandboxRecord.create(projectId, collection.getId(), key, data)));
        }
        return written;
    }

    /**
     * Ensures the record carries the id the caller will later fetch it by. A generated key
     * is written back into the payload rather than kept beside it, so what the caller reads
     * back always contains the id they can use.
     */
    private Map<String, Object> withKey(SandboxCollection collection, Map<String, Object> payload) {
        Map<String, Object> data = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        String field = collection.getKeyField();
        Object existing = data.get(field);
        String key = existing == null || String.valueOf(existing).isBlank()
                ? generateKey(collection)
                : String.valueOf(existing);
        data.put(field, key);
        return data;
    }

    private long estimateBytes(Map<String, Object> data) {
        try {
            return mapper.writeValueAsBytes(data).length;
        } catch (RuntimeException e) {
            // Never fail a write on an accounting estimate — but never return 0 either.
            // An unmeasurable record must still consume quota.
            return 1024;
        }
    }

    private static String generateKey(SandboxCollection collection) {
        String code = collection.getCode();
        String prefix = code.length() > 3 ? code.substring(0, 3) : code;
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Used by the update paths, which must not let a body re-identify an existing record. */
    public long sizeOf(Map<String, Object> data) {
        return estimateBytes(data);
    }

    /** Charges growth on an edit. Shrinking edits are never refused. */
    public void requireCapacity(UUID projectId, long extraBytes) {
        quotas.requireCapacityFor(projectId, 0, extraBytes);
    }
}
