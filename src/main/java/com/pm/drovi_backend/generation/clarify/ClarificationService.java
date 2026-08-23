package com.pm.drovi_backend.generation.clarify;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.common.ErrorCode;
import com.pm.drovi_backend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Raising doubts, answering them, and letting generation carry on once none are left.
 *
 * <h2>Why generation stops rather than guesses</h2>
 *
 * A guess here is not a small error. "Give me a blocked card" with three card endpoints and a
 * record carrying both {@code status} and {@code blocked} has several readings, and picking one
 * silently produces a sandbox that <em>looks</em> right — so the user builds against it and
 * finds out later. Asking costs a minute; being confidently wrong costs an afternoon.
 *
 * <h2>Why "you decide" is a first-class answer</h2>
 *
 * This is a mock. For most doubts a plausible assumption beats a blocked generation, and a user
 * who does not care should not be made to care. So {@code allowsAssumption} is the default and
 * the assumption is <em>recorded</em> — an assumption nobody can look up later is
 * indistinguishable from a bug.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClarificationService {

    private final ClarificationStore store;
    private final ProjectService projects;
    private final ClarificationResumer resumer;

    @Transactional(readOnly = true)
    public List<Clarification> forProject(UUID accountId, UUID projectId) {
        projects.require(accountId, projectId);
        return store.forProject(projectId);
    }

    /**
     * The user chose one of the options, or wrote their own answer.
     *
     * @param optionId one of the offered options, or null when {@code freeText} is the answer
     */
    @Transactional
    public Clarification answer(UUID accountId, UUID clarificationId, String optionId, String freeText) {
        Clarification doubt = require(accountId, clarificationId);
        String answer = resolveAnswerText(doubt, optionId, freeText);

        if (!store.resolve(doubt.id(), Clarification.Status.ANSWERED, answer, optionId)) {
            throw new DroviException(ErrorCode.CONFLICT, "That question has already been answered.");
        }
        log.info("clarification.answered id={} projectId={}", doubt.id(), doubt.projectId());
        return finish(accountId, doubt);
    }

    /**
     * "You decide." A real answer, not a way of skipping the question — and the decision is
     * recorded so it can be found and changed later.
     */
    @Transactional
    public Clarification assume(UUID accountId, UUID clarificationId) {
        Clarification doubt = require(accountId, clarificationId);
        if (!doubt.allowsAssumption()) {
            // Rare, and deliberate: some doubts are precisely the thing the user asked for, and
            // guessing there produces a sandbox confidently wrong about their own request.
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "This one needs an answer — a guess here would change what your sandbox does.");
        }
        String assumed = doubt.options().isEmpty()
                ? "Assume something plausible."
                : "Assume: " + doubt.options().getFirst().label();

        if (!store.resolve(doubt.id(), Clarification.Status.ASSUMED, assumed,
                doubt.options().isEmpty() ? null : doubt.options().getFirst().id())) {
            throw new DroviException(ErrorCode.CONFLICT, "That question has already been answered.");
        }
        log.info("clarification.assumed id={} projectId={}", doubt.id(), doubt.projectId());
        return finish(accountId, doubt);
    }

    @Transactional(readOnly = true)
    public boolean isWaitingOnUser(UUID projectId) {
        return store.hasOpen(projectId);
    }

    /**
     * Answering the last open doubt is what restarts the generation. Handing that to a separate
     * collaborator keeps this class from depending on the pipeline, which depends on this one.
     */
    private Clarification finish(UUID accountId, Clarification doubt) {
        if (doubt.projectId() != null && !store.hasOpen(doubt.projectId())) {
            resumer.resume(accountId, doubt.projectId());
        }
        return require(accountId, doubt.id());
    }

    private Clarification require(UUID accountId, UUID clarificationId) {
        return store.find(accountId, clarificationId)
                .orElseThrow(() -> DroviException.notFound("No such question."));
    }

    private static String resolveAnswerText(Clarification doubt, String optionId, String freeText) {
        if (optionId != null) {
            return doubt.options().stream()
                    .filter(option -> option.id().equals(optionId))
                    .findFirst()
                    .map(Clarification.Option::label)
                    .orElseThrow(() -> new DroviException(ErrorCode.VALIDATION_FAILED,
                            "That is not one of the offered options."));
        }
        if (freeText == null || freeText.isBlank()) {
            throw new DroviException(ErrorCode.VALIDATION_FAILED,
                    "Choose an option, write an answer, or ask us to decide.");
        }
        return freeText.trim();
    }
}
