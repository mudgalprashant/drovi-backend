package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.SandboxCollection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SandboxCollectionRepository extends JpaRepository<SandboxCollection, UUID> {

    Optional<SandboxCollection> findByProjectIdAndCode(UUID projectId, String code);

    /** Always scoped by project — never {@code findById} alone. See invariant 4. */
    Optional<SandboxCollection> findByIdAndProjectId(UUID id, UUID projectId);

    List<SandboxCollection> findByProjectIdOrderByCodeAsc(UUID projectId);
}
