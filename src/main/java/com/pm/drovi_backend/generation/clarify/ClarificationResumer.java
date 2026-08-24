package com.pm.drovi_backend.generation.clarify;

import java.util.UUID;

/**
 * What happens when the last open doubt is answered.
 *
 * <p>An interface only so that {@link ClarificationService} does not depend on the pipeline,
 * which already depends on it. The pipeline implements this and picks the generation back up
 * from the step that paused.
 */
public interface ClarificationResumer {

    void resume(UUID accountId, UUID projectId);
}
