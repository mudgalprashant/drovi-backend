package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.SandboxProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SandboxProjectRepository extends JpaRepository<SandboxProject, UUID> {

    Optional<SandboxProject> findByProjectKey(String projectKey);
}
