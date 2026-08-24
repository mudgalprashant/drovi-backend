package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.JobChain;
import com.pm.drovi_backend.generation.JobKind;
import com.pm.drovi_backend.generation.JobStore;
import com.pm.drovi_backend.generation.NewJob;
import com.pm.drovi_backend.generation.clarify.ClarificationResumer;
import com.pm.drovi_backend.generation.clarify.ClarificationStore;
import com.pm.drovi_backend.generation.clarify.RaisedQuestion;
import com.pm.drovi_backend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What follows what: RESEARCH → SPEC → one SEED per collection → the project is READY.
 *
 * <p>The only class that knows the order. {@code JobRunner} claims and retries; this decides
 * what the pipeline is, so changing the shape of generation does not mean editing the
 * machinery that runs it.
 *
 * <h2>Why SEED fans out into one job per collection</h2>
 *
 * Not for parallelism — the runner takes one job per tick and that is deliberate. It is so the
 * runner's own cadence spaces the model calls out against a 15-requests-per-minute tier, and
 * so a collection whose records will not generate retries alone instead of dragging four
 * successful ones back through the model.
 *
 * <h2>How the last step knows it is last</h2>
 *
 * It cannot, and does not try. A SEED job asks whether anything else is still outstanding for
 * the project; the one that finds nothing is the last, whichever it happens to be. Counting
 * expected steps instead would need the count to survive a retry, a requeue and a failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationPipeline implements JobChain, ClarificationResumer {

    private final JobStore jobs;
    private final ProjectService projects;
    /**
     * The store rather than {@code ClarificationService}, deliberately. The service depends on
     * this class to resume a paused generation, so depending on the service here would be a
     * constructor cycle. Raising a doubt and reading a settled one are store-level operations;
     * answering one — which is what needs the resume — is not.
     */
    private final ClarificationStore clarifications;

    /**
     * A step's successors, unless it raised doubts — in which case the chain <strong>stops and
     * waits for the user</strong>.
     *
     * <p>That pause is the point. A step that is unsure and carries on regardless produces a
     * sandbox that looks right and is wrong, and the user finds out after building against it.
     * The generation resumes from {@link #resume} the moment the last question is answered.
     */
    @Override
    public List<NewJob> after(GenerationJob job, Map<String, Object> result) {
        if (raiseAll(job, RaisedQuestion.from(result.get("questions"))) > 0) {
            log.info("pipeline.waiting.for.user jobId={} projectId={}", job.id(), job.projectId());
            return List.of();
        }
        return successorsOf(job, result);
    }

    /**
     * Picks the generation back up where it paused.
     *
     * <p>It works out where that was by asking the database rather than by remembering: the most
     * recent succeeded job for the project is, by definition, the one whose successors were
     * never enqueued. Storing "what to do next" beside the questions would be a second source of
     * truth that has to survive a retry and a failure.
     */
    @Override
    public void resume(UUID accountId, UUID projectId) {
        jobs.lastSucceededFor(projectId).ifPresentOrElse(
                job -> {
                    List<NewJob> next = successorsOf(job, jobs.findResult(job.id()).orElse(Map.of()));
                    jobs.enqueueAll(job.id(), next);
                    log.info("pipeline.resumed projectId={} after={} enqueued={}",
                            projectId, job.kind(), next.size());
                },
                () -> log.warn("pipeline.resume.nothingToResume projectId={}", projectId));
    }

    private List<NewJob> successorsOf(GenerationJob job, Map<String, Object> result) {
        return switch (job.kind()) {
            case RESEARCH -> List.of(new NewJob(JobKind.SPEC,
                    "Turn the research into endpoints",
                    // Findings by reference — they stay in the row that produced them rather
                    // than being copied into every job downstream. Answers by value, because a
                    // step that is not told what was already decided asks it again.
                    Map.of("researchJobId", job.id().toString(),
                            "clarifications", answersFor(job.projectId()))));

            case SPEC -> seedJobsFor(result);

            // A SEED job is the end of a branch. Whichever one finds nothing else outstanding
            // finishes the generation.
            case SEED -> {
                finishIfLast(job);
                yield List.of();
            }

            // A revision that asked a question changed nothing, so what follows it is ITSELF —
            // the same instruction, re-run once the answers exist. Every other step's questions
            // are about how to proceed; a revision's are about what to do, so there is nothing
            // to proceed to.
            case REVISE -> Boolean.TRUE.equals(result.get("deferred"))
                    ? List.of(new NewJob(JobKind.REVISE,
                            String.valueOf(result.getOrDefault("instruction", job.prompt())),
                            Map.of("instruction", result.getOrDefault("instruction", job.prompt()),
                                    "clarifications", answersFor(job.projectId()))))
                    : List.of();
        };
    }

    @Override
    public void afterFailure(GenerationJob job, String errorCode) {
        if (job.projectId() == null) {
            return;
        }
        // A generation that stopped must leave a project that SAYS it failed. A project that
        // simply never becomes ready is indistinguishable from one still working, forever.
        projects.markGenerationFailed(job.accountId(), job.projectId());
        log.info("pipeline.failed jobId={} kind={} projectId={} errorCode={}",
                job.id(), job.kind(), job.projectId(), errorCode);
    }

    /**
     * SPEC reports the collections it created, by code and id, precisely so this does not have
     * to go back to the database and guess which of a project's collections were its doing.
     */
    @SuppressWarnings("unchecked")
    private List<NewJob> seedJobsFor(Map<String, Object> specResult) {
        if (!(specResult.get("collectionIds") instanceof Map<?, ?> ids) || ids.isEmpty()) {
            log.warn("pipeline.spec.noCollections — nothing to seed");
            return List.of();
        }
        List<NewJob> seeds = new ArrayList<>();
        ((Map<String, Object>) ids).forEach((code, id) -> seeds.add(new NewJob(
                JobKind.SEED,
                "Generate example " + code,
                Map.of("collectionId", String.valueOf(id)))));
        return seeds;
    }

    /** @return how many doubts are now open. Zero means the chain carries on. */
    private int raiseAll(GenerationJob job, List<RaisedQuestion> questions) {
        for (RaisedQuestion question : questions) {
            clarifications.raise(job.accountId(), job.projectId(), job.id(), job.threadId(),
                    question.question(), question.detail(), question.subject(),
                    question.options(), question.allowsAssumption());
        }
        return questions.size();
    }

    /** What the user has already settled, in a form a prompt can carry. */
    private List<Map<String, String>> answersFor(UUID projectId) {
        if (projectId == null) {
            return List.of();
        }
        return clarifications.resolvedFor(projectId).stream()
                .map(doubt -> Map.of("question", doubt.question(), "answer", doubt.asResolvedInstruction()))
                .toList();
    }

    private void finishIfLast(GenerationJob job) {
        if (job.projectId() == null || jobs.hasUnfinishedJobs(job.projectId(), job.id())) {
            return;
        }
        projects.markReady(job.accountId(), job.projectId());
        log.info("pipeline.completed projectId={}", job.projectId());
    }
}
