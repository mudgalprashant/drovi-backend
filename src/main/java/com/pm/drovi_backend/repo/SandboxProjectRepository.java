package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.SandboxProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SandboxProjectRepository extends JpaRepository<SandboxProject, UUID> {


    /**
     * The console's only lookup. Scoping by account in the query — rather than loading by
     * id and comparing afterwards — is what makes a forgotten ownership check impossible
     * rather than merely unlikely.
     */
    Optional<SandboxProject> findByIdAndAccountId(UUID id, UUID accountId);

    List<SandboxProject> findByAccountIdAndArchivedAtIsNullOrderByCreatedAtDesc(UUID accountId);

    /** Archived projects do not count against the plan; they no longer serve. */
    long countByAccountIdAndArchivedAtIsNull(UUID accountId);

}
