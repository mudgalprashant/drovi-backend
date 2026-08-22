package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.SandboxCollection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SandboxCollectionRepository extends JpaRepository<SandboxCollection, UUID> {

    Optional<SandboxCollection> findByProjectIdAndCode(UUID projectId, String code);
}
