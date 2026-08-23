package com.pm.drovi_backend.generation.pipeline;

import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.JobChain;
import com.pm.drovi_backend.generation.JobKind;
import com.pm.drovi_backend.generation.JobStore;
import com.pm.drovi_backend.generation.NewJob;
import com.pm.drovi_backend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
public class GenerationPipeline implements JobChain {

    private final JobStore jobs;
    private final ProjectService projects;

    @Override
    public List<NewJob> after(GenerationJob job, Map<String, Object> result) {
        return switch (job.kind()) {
            case RESEARCH -> List.of(new NewJob(JobKind.SPEC,
                    "Turn the research into endpoints",
                    // By reference, not by value: the findings stay in the row that produced
                    // them rather than being copied into every job downstream of it.
                    Map.of("researchJobId", job.id().toString())));

            case SPEC -> seedJobsFor(result);

            // A SEED job is the end of a branch. Whichever one finds nothing else outstanding
            // finishes the generation.
            case SEED -> {
                finishIfLast(job);
                yield List.of();
            }

            // REVISE edits a sandbox that already exists. Nothing follows it.
            case REVISE -> List.of();
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

    private void finishIfLast(GenerationJob job) {
        if (job.projectId() == null || jobs.hasUnfinishedJobs(job.projectId(), job.id())) {
            return;
        }
        projects.markReady(job.accountId(), job.projectId());
        log.info("pipeline.completed projectId={}", job.projectId());
    }
}
