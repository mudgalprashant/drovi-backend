package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.ProjectApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectApiKeyRepository extends JpaRepository<ProjectApiKey, UUID> {

    List<ProjectApiKey> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<ProjectApiKey> findByIdAndProjectId(UUID id, UUID projectId);
}
