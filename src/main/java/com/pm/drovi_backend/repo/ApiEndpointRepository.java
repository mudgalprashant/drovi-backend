package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.ApiEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiEndpointRepository extends JpaRepository<ApiEndpoint, UUID> {

    /**
     * Ordered so the caller can take the first match: more literal templates first, and
     * ties broken by path so the winner is stable rather than whatever the planner
     * returned that day. A non-deterministic route table is a bug that only appears in
     * production.
     */
    List<ApiEndpoint> findByProjectIdAndMethodOrderBySpecificityDescPathTemplateAsc(UUID projectId, String method);

    /** Console listing. Ordered so the spec reads like a Postman collection. */
    List<ApiEndpoint> findByProjectIdOrderByCollectionIdAscSortOrderAscPathTemplateAsc(UUID projectId);

    List<ApiEndpoint> findByCollectionId(UUID collectionId);

    Optional<ApiEndpoint> findByIdAndProjectId(UUID id, UUID projectId);

    /** The route table is unique on (project, method, path) — checked before insert. */
    Optional<ApiEndpoint> findByProjectIdAndMethodAndPathTemplate(UUID projectId, String method, String pathTemplate);

    long countByProjectId(UUID projectId);
}
