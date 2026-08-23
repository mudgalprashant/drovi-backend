package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.ApiCollection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiCollectionRepository extends JpaRepository<ApiCollection, UUID> {

    Optional<ApiCollection> findByProjectIdAndName(UUID projectId, String name);

    Optional<ApiCollection> findByIdAndProjectId(UUID id, UUID projectId);

    List<ApiCollection> findByProjectIdOrderBySortOrderAscNameAsc(UUID projectId);
}
