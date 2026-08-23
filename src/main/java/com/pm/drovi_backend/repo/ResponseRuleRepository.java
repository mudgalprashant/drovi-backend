package com.pm.drovi_backend.repo;

import com.pm.drovi_backend.domain.ResponseRule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ResponseRuleRepository extends JpaRepository<ResponseRule, UUID> {

    List<ResponseRule> findByEndpointIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(UUID endpointId);

    /** Console listing — includes disabled rules, which the runtime's query does not. */
    List<ResponseRule> findByEndpointIdOrderByPriorityAscCreatedAtAsc(UUID endpointId);

    java.util.Optional<ResponseRule> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * Consumes one use of an N-shot rule.
     *
     * <p>Done as a conditional UPDATE rather than read-modify-write because two concurrent
     * calls to a "fail once" rule must produce exactly one failure. The {@code > 0} guard
     * is what makes that true without holding a row lock across the response.
     */
    @Modifying
    @Query("""
            UPDATE ResponseRule r SET r.remainingUses = r.remainingUses - 1
             WHERE r.id = :id AND r.remainingUses > 0
            """)
    int consumeUse(UUID id);
}
