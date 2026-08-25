package com.pm.drovi_backend.chat;

import com.pm.drovi_backend.config.DroviProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Tells the conversation what the pipeline just did — but only the parts a person cares about.
 *
 * <p>A generation is five or six jobs and most of them are plumbing. Narrating all of them would
 * bury the two messages that matter (a question, and "it is ready") under a progress log nobody
 * reads. So the pipeline calls this at exactly the moments a user would want to hear something.
 *
 * <p>Every method is best-effort: a transcript is a convenience, and failing to write one must
 * never fail the generation it was describing. A project with no thread — one driven through the
 * REST API rather than chat — silently gets no narration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatNarrator {

    private final ChatStore chats;
    private final DroviProperties properties;

    /** A question is the one moment the generation genuinely needs the user back. */
    public void asked(UUID projectId, String question) {
        say(projectId, "Before I carry on: " + question);
    }

    /** The message the whole thing exists to produce. */
    public void ready(UUID projectId) {
        say(projectId, "Your sandbox is ready. Point your app at %s and it will answer."
                .formatted(properties.baseUrlFor(projectId)));
    }

    public void failed(UUID projectId, String reason) {
        say(projectId, "I could not finish that. " + reason);
    }

    public void revised(UUID projectId, String summary) {
        say(projectId, summary == null || summary.isBlank() ? "Done — your sandbox is updated." : summary);
    }

    private void say(UUID projectId, String text) {
        if (projectId == null) {
            return;
        }
        try {
            chats.newestThreadFor(projectId)
                    .ifPresent(threadId -> chats.append(threadId, ChatMessage.Role.ASSISTANT, text));
        } catch (RuntimeException e) {
            log.warn("chat.narration.failed projectId={}", projectId, e);
        }
    }
}
