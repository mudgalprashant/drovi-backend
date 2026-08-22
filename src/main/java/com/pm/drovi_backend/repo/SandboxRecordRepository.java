package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.SandboxRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SandboxRecordRepository extends JpaRepository<SandboxRecord, UUID> {

    Optional<SandboxRecord> findByCollectionIdAndRecordKey(UUID collectionId, String recordKey);

    /**
     * Containment ({@code @>}), not a chain of {@code ->>} comparisons, so the GIN index
     * on {@code data} is actually used. The filter arrives as a JSON object built from the
     * caller's query string and is bound as a parameter — never concatenated.
     *
     * <p>{@code '{}'} contains everything, so an unfiltered list needs no second query.
     */
    @Query(value = """
            SELECT * FROM sandbox_record
             WHERE project_id = :projectId
               AND collection_id = :collectionId
               AND data @> CAST(:filter AS jsonb)
             ORDER BY created_at DESC, id
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<SandboxRecord> findFiltered(@Param("projectId") UUID projectId,
                                     @Param("collectionId") UUID collectionId,
                                     @Param("filter") String filter,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Query(value = """
            SELECT count(*) FROM sandbox_record
             WHERE project_id = :projectId
               AND collection_id = :collectionId
               AND data @> CAST(:filter AS jsonb)
            """, nativeQuery = true)
    long countFiltered(@Param("projectId") UUID projectId,
                       @Param("collectionId") UUID collectionId,
                       @Param("filter") String filter);
}
