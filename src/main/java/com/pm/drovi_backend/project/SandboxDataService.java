package com.pm.drovi_backend.project;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.domain.SandboxCollection;
import com.pm.drovi_backend.domain.SandboxRecord;
import com.pm.drovi_backend.repo.SandboxCollectionRepository;
import com.pm.drovi_backend.repo.SandboxRecordRepository;
import com.pm.drovi_backend.runtime.RecordWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The console's view of a project's data: collections of records, and the records in them.
 *
 * <p>This is what lets a user seed "five customers whose card was blocked" without touching
 * SQL — which is the whole reason invariant 1 says a sandbox is data rather than scripts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SandboxDataService {

    /** A batch large enough to be useful and small enough to stay inside one transaction. */
    public static final int MAX_BULK_RECORDS = 1000;

    private final SandboxCollectionRepository collections;
    private final SandboxRecordRepository records;
    private final RecordWriter recordWriter;
    private final ProjectService projects;

    // --- collections ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SandboxCollection> listCollections(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);
        return collections.findByProjectIdOrderByCodeAsc(projectId);
    }

    @Transactional
    public SandboxCollection createCollection(UUID accountId, UUID projectId, String code,
                                              String displayName, String description,
                                              Map<String, Object> recordSchema, String keyField) {
        projects.require(accountId, projectId);
        collections.findByProjectIdAndCode(projectId, code).ifPresent(existing -> {
            throw new DroviException(ErrorCode.CONFLICT,
                    "A data collection called '%s' already exists in this project.".formatted(code));
        });
        SandboxCollection created = collections.save(SandboxCollection.create(
                projectId, code, displayName == null ? code : displayName,
                description, recordSchema, keyField));
        log.info("collection.created collectionId={} projectId={} code={}",
                created.getId(), projectId, code);
        return created;
    }

    @Transactional(readOnly = true)
    public SandboxCollection requireCollection(UUID accountId, UUID projectId, UUID collectionId) {
        projects.require(accountId, projectId);
        return collections.findByIdAndProjectId(collectionId, projectId)
                .orElseThrow(() -> DroviException.notFound("No such data collection."));
    }

    @Transactional
    public SandboxCollection updateCollection(UUID accountId, UUID projectId, UUID collectionId,
                                              String displayName, String description,
                                              Map<String, Object> recordSchema) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        collection.update(displayName, description, recordSchema);
        return collection;
    }

    /**
     * Deletes the collection and, by cascade, every record in it. Unlike archiving a
     * project this really is destructive — the console must confirm it, because the
     * trigger-maintained counters make the space come back but the data does not.
     */
    @Transactional
    public void deleteCollection(UUID accountId, UUID projectId, UUID collectionId) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        collections.delete(collection);
        log.info("collection.deleted collectionId={} projectId={} records={}",
                collectionId, projectId, collection.getRecordCount());
    }

    // --- records -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SandboxRecord> listRecords(UUID accountId, UUID projectId, UUID collectionId,
                                           int limit, int offset) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        // "{}" contains everything, so the same indexed query serves filtered and
        // unfiltered listing.
        return records.findFiltered(projectId, collection.getId(), "{}",
                Math.clamp(limit, 1, 200), Math.max(0, offset));
    }

    @Transactional(readOnly = true)
    public long countRecords(UUID accountId, UUID projectId, UUID collectionId) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        return records.countFiltered(projectId, collection.getId(), "{}");
    }

    /**
     * Creates one record or many. Bulk is the point: seeding a realistic sandbox means
     * hundreds of rows, and doing that one HTTP call at a time is both slow and a way to
     * end up half-loaded when quota runs out midway.
     */
    @Transactional
    public List<SandboxRecord> createRecords(UUID accountId, UUID projectId, UUID collectionId,
                                             List<Map<String, Object>> payloads) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        if (payloads == null || payloads.isEmpty()) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED, "No records supplied.");
        }
        if (payloads.size() > MAX_BULK_RECORDS) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "At most %d records per request; received %d."
                            .formatted(MAX_BULK_RECORDS, payloads.size()));
        }
        try {
            List<SandboxRecord> written = recordWriter.writeAll(projectId, collection, payloads);
            log.info("records.created projectId={} collectionId={} count={}",
                    projectId, collectionId, written.size());
            return written;
        } catch (RecordWriter.DuplicateRecordException e) {
            throw new DroviException(ErrorCode.CONFLICT, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public SandboxRecord requireRecord(UUID accountId, UUID projectId, UUID collectionId, String recordKey) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        return records.findByCollectionIdAndRecordKey(collection.getId(), recordKey)
                .orElseThrow(() -> DroviException.notFound("No such record."));
    }

    /**
     * Shallow merge, matching the sandbox's own PATCH semantics. The key field is restored
     * afterwards so a body cannot silently re-identify an existing record — that would
     * strand it under a key nothing looks up.
     */
    @Transactional
    public SandboxRecord updateRecord(UUID accountId, UUID projectId, UUID collectionId,
                                      String recordKey, Map<String, Object> patch) {
        SandboxCollection collection = requireCollection(accountId, projectId, collectionId);
        SandboxRecord record = records.findByCollectionIdAndRecordKey(collection.getId(), recordKey)
                .orElseThrow(() -> DroviException.notFound("No such record."));

        Map<String, Object> merged = new LinkedHashMap<>(record.getData());
        if (patch != null) {
            merged.putAll(patch);
        }
        merged.put(collection.getKeyField(), recordKey);

        long delta = recordWriter.sizeOf(merged) - recordWriter.sizeOf(record.getData());
        if (delta > 0) {
            // Only growth is charged. A shrinking edit must never be refused because the
            // project is already near its limit.
            recordWriter.requireCapacity(projectId, delta);
        }
        record.replaceData(merged);
        return record;
    }

    @Transactional
    public void deleteRecord(UUID accountId, UUID projectId, UUID collectionId, String recordKey) {
        SandboxRecord record = requireRecord(accountId, projectId, collectionId, recordKey);
        records.delete(record);
    }
}
