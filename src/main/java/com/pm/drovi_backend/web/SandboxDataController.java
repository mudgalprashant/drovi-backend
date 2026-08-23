package com.pm.drovi_backend.web;

import com.pm.drovi_backend.domain.SandboxCollection;
import com.pm.drovi_backend.domain.SandboxRecord;
import com.pm.drovi_backend.identity.DroviPrincipal;
import com.pm.drovi_backend.project.SandboxDataService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A project's data: collections, and the records inside them.
 *
 * <p>This is what makes invariant 1 usable. "Give me five customers whose card was blocked
 * in the last 30 days" is a POST here, not a code change — so a sandbox's behaviour can
 * change in a second.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/collections")
@RequiredArgsConstructor
class SandboxDataController {

    private final SandboxDataService data;

    // --- requests ------------------------------------------------------------

    record CreateCollectionRequest(
            /* Used in URLs and as a jsonb key, so it is restricted at the edge rather
               than sanitised later. */
            @NotBlank @Pattern(regexp = "[a-z0-9_-]{1,60}",
                    message = "must be lowercase letters, digits, hyphen or underscore")
            String code,
            @Size(max = 120) String displayName,
            @Size(max = 500) String description,
            Map<String, Object> recordSchema,
            @Size(max = 60) String keyField) {
    }

    record UpdateCollectionRequest(@Size(max = 120) String displayName,
                                   @Size(max = 500) String description,
                                   Map<String, Object> recordSchema) {
    }

    /**
     * Accepts one record or many. Bulk is the normal case — seeding a believable sandbox
     * means hundreds of rows, and one HTTP call each would be slow and would leave the
     * collection half-loaded if quota ran out partway.
     */
    record CreateRecordsRequest(Map<String, Object> record, List<Map<String, Object>> records) {

        List<Map<String, Object>> asList() {
            if (records != null && !records.isEmpty()) {
                return records;
            }
            return record == null ? List.of() : List.of(record);
        }
    }

    // --- responses -----------------------------------------------------------

    record CollectionResponse(String id, String code, String displayName, String description,
                              String keyField, Map<String, Object> recordSchema,
                              long recordCount, long storedBytes, Instant createdAt) {
    }

    record RecordResponse(String recordKey, Map<String, Object> data,
                          Instant createdAt, Instant updatedAt) {
    }

    record RecordPage(List<RecordResponse> items, long total, int limit, int offset) {
    }

    // --- collections ---------------------------------------------------------

    @GetMapping
    List<CollectionResponse> listCollections(@AuthenticationPrincipal DroviPrincipal principal,
                                             @PathVariable UUID projectId) {
        return data.listCollections(principal.accountId(), projectId).stream()
                .map(SandboxDataController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CollectionResponse createCollection(@AuthenticationPrincipal DroviPrincipal principal,
                                        @PathVariable UUID projectId,
                                        @Valid @RequestBody CreateCollectionRequest request) {
        return toResponse(data.createCollection(principal.accountId(), projectId, request.code(),
                request.displayName(), request.description(), request.recordSchema(),
                request.keyField()));
    }

    @GetMapping("/{collectionId}")
    CollectionResponse getCollection(@AuthenticationPrincipal DroviPrincipal principal,
                                     @PathVariable UUID projectId, @PathVariable UUID collectionId) {
        return toResponse(data.requireCollection(principal.accountId(), projectId, collectionId));
    }

    @PatchMapping("/{collectionId}")
    CollectionResponse updateCollection(@AuthenticationPrincipal DroviPrincipal principal,
                                        @PathVariable UUID projectId, @PathVariable UUID collectionId,
                                        @Valid @RequestBody UpdateCollectionRequest request) {
        return toResponse(data.updateCollection(principal.accountId(), projectId, collectionId,
                request.displayName(), request.description(), request.recordSchema()));
    }

    @DeleteMapping("/{collectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCollection(@AuthenticationPrincipal DroviPrincipal principal,
                          @PathVariable UUID projectId, @PathVariable UUID collectionId) {
        data.deleteCollection(principal.accountId(), projectId, collectionId);
    }

    // --- records -------------------------------------------------------------

    @GetMapping("/{collectionId}/records")
    RecordPage listRecords(@AuthenticationPrincipal DroviPrincipal principal,
                           @PathVariable UUID projectId, @PathVariable UUID collectionId,
                           @RequestParam(defaultValue = "25") int limit,
                           @RequestParam(defaultValue = "0") int offset) {
        List<RecordResponse> items = data
                .listRecords(principal.accountId(), projectId, collectionId, limit, offset)
                .stream().map(SandboxDataController::toResponse).toList();
        long total = data.countRecords(principal.accountId(), projectId, collectionId);
        return new RecordPage(items, total, limit, offset);
    }

    @PostMapping("/{collectionId}/records")
    @ResponseStatus(HttpStatus.CREATED)
    List<RecordResponse> createRecords(@AuthenticationPrincipal DroviPrincipal principal,
                                       @PathVariable UUID projectId, @PathVariable UUID collectionId,
                                       @RequestBody CreateRecordsRequest request) {
        return data.createRecords(principal.accountId(), projectId, collectionId, request.asList())
                .stream().map(SandboxDataController::toResponse).toList();
    }

    @GetMapping("/{collectionId}/records/{recordKey}")
    RecordResponse getRecord(@AuthenticationPrincipal DroviPrincipal principal,
                             @PathVariable UUID projectId, @PathVariable UUID collectionId,
                             @PathVariable String recordKey) {
        return toResponse(data.requireRecord(principal.accountId(), projectId, collectionId, recordKey));
    }

    @PatchMapping("/{collectionId}/records/{recordKey}")
    RecordResponse updateRecord(@AuthenticationPrincipal DroviPrincipal principal,
                                @PathVariable UUID projectId, @PathVariable UUID collectionId,
                                @PathVariable String recordKey,
                                @RequestBody Map<String, Object> patch) {
        return toResponse(data.updateRecord(principal.accountId(), projectId, collectionId,
                recordKey, patch));
    }

    @DeleteMapping("/{collectionId}/records/{recordKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRecord(@AuthenticationPrincipal DroviPrincipal principal,
                      @PathVariable UUID projectId, @PathVariable UUID collectionId,
                      @PathVariable String recordKey) {
        data.deleteRecord(principal.accountId(), projectId, collectionId, recordKey);
    }

    private static CollectionResponse toResponse(SandboxCollection c) {
        return new CollectionResponse(c.getId().toString(), c.getCode(), c.getDisplayName(),
                c.getDescription(), c.getKeyField(), c.getRecordSchema(),
                // Trigger-maintained, and the numbers quota is enforced against — so the
                // console can show headroom before a write fails with 507.
                c.getRecordCount(), c.getStoredBytes(), c.getCreatedAt());
    }

    private static RecordResponse toResponse(SandboxRecord r) {
        return new RecordResponse(r.getRecordKey(), r.getData(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
