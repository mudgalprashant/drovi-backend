package com.pm.drovi_backend.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one door to a model. Nothing else in the system may call an {@link AiProvider}.
 *
 * <p>It exists because the adapter, the ledger and the caps are not three features — they
 * are one. An adapter reachable without the guard is an adapter that can spend before spend
 * can be measured or stopped, which is the most expensive mistake available in this
 * codebase. Keeping them behind a single method is what makes that mistake require
 * deliberate effort rather than forgetfulness.
 *
 * <p>The sequence is fixed:
 *
 * <ol>
 *   <li>resolve the active provider, its adapter and its key — fail closed if any is missing
 *   <li>choose the model for the purpose
 *   <li>ask {@link SpendGuard}. A refusal is ledgered as {@code CAPPED} and nothing is sent
 *   <li>call the provider, timed, <em>outside</em> any transaction
 *   <li>ledger the outcome — success, error or timeout, always
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiGateway {

    private final ProviderRegistry providers;
    private final ModelRouter router;
    private final SpendGuard spendGuard;
    private final AiCallLedger ledger;
    private final PricingService pricing;

    /**
     * Deliberately <strong>not</strong> {@code @Transactional}, and it refuses to run inside
     * someone else's transaction — see {@link #refuseIfTransactional}.
     */
    public AiResponse call(AiCallContext context, AiRequest request) {
        refuseIfTransactional();

        ResolvedProvider provider = resolve();
        String model = router.modelFor(request.purpose(), provider.config());

        try {
            spendGuard.requireSpendAllowed(context.accountId());
        } catch (AiCappedException capped) {
            // The refusal is itself a ledger row. It is the only evidence that a control
            // fired, and without it a quiet day and a capped day look identical.
            ledger.record(context, provider.config().code(), model, request.purpose(),
                    AiCallStatus.CAPPED, 0, 0, 0, null);
            throw capped;
        }

        long startedAt = System.nanoTime();
        try {
            AiResponse response = provider.adapter()
                    .complete(provider.config(), provider.apiKey(), model, request);
            ledgerOutcome(context, provider, model, request, AiCallStatus.OK,
                    response.inputTokens(), response.outputTokens(), startedAt);
            return response;

        } catch (AiProviderException e) {
            // Ledgered with zero tokens because the provider did not report any — not
            // because none were consumed. A failed call that read a long prompt still cost
            // money; the ledger records what is known, and the gap is a known one.
            ledgerOutcome(context, provider, model, request, e.getStatus(), 0, 0, startedAt);
            log.warn("ai.call.failed purpose={} model={} status={} detail={}",
                    request.purpose(), model, e.getStatus(), e.getMessage());
            throw new AiProviderException(e.getStatus(), "The model call did not complete.");
        }
    }

    private ResolvedProvider resolve() {
        ProviderConfig config = providers.activeProvider();
        if (config == null) {
            throw new AiUnavailableException(
                    "No ai_provider_config row is active. Generation cannot run until one is.");
        }
        return new ResolvedProvider(config, providers.adapterFor(config), providers.apiKeyFor(config));
    }

    private void ledgerOutcome(AiCallContext context, ResolvedProvider provider, String model,
                               AiRequest request, AiCallStatus status,
                               int inputTokens, int outputTokens, long startedAtNanos) {
        int latencyMs = (int) Math.min(Integer.MAX_VALUE,
                (System.nanoTime() - startedAtNanos) / 1_000_000L);
        PricingService.Rate rate = pricing.rateFor(provider.config().code(), model, java.time.Instant.now());
        ledger.record(context, provider.config().code(), model, request.purpose(), status,
                inputTokens, outputTokens, pricing.costMicros(rate, inputTokens, outputTokens), latencyMs);
    }

    /**
     * A generation call takes minutes. Holding a database connection open for that long
     * exhausts a free-tier pool and takes out every other request in the process — a failure
     * that presents as "the whole app is slow", nowhere near the code that caused it.
     *
     * <p>An {@link IllegalStateException} rather than a {@code DroviException}: this is a
     * programming error to be caught in a test, not something a user did.
     */
    private void refuseIfTransactional() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A model call must not run inside a transaction — commit first, then call, then record. "
                            + "See docs/02-implementation/implementation-plan.md § Phase 3.");
        }
    }
}
