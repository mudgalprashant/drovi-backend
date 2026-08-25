package com.pm.drovi_backend.chat;

import com.pm.drovi_backend.common.DroviException;
import com.pm.drovi_backend.generation.GenerationJob;
import com.pm.drovi_backend.generation.GenerationService;
import com.pm.drovi_backend.project.ApiSpecService;
import com.pm.drovi_backend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Chat as the front door: one place a user says what they want, whatever stage they are at.
 *
 * <h2>Why a message is not a command</h2>
 *
 * The same sentence means different things depending on the sandbox. "Give me a blocked card"
 * against an empty project is a request to build one; against a built project it is a change to
 * its data. Making the user pick the right endpoint for their sentence is asking them to know
 * something about our pipeline, so this decides instead — build if there is nothing to change,
 * revise if there is.
 *
 * <h2>Why the transcript is written here and not by the pipeline</h2>
 *
 * Only some of a generation's events are worth telling a person about. The pipeline knows when a
 * job succeeded; it does not know that four of the five steps are uninteresting. So the pipeline
 * calls {@link ChatNarrator} at the few moments a user would want a message, and everything else
 * stays in the job history where it belongs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatStore chats;
    private final ProjectService projects;
    private final ApiSpecService spec;
    private final GenerationService generations;

    @Transactional
    public ChatStore.Thread start(UUID accountId, UUID projectId, String title) {
        projects.require(accountId, projectId);
        return chats.createThread(accountId, projectId, title);
    }

    /**
     * Say something, and have it acted on.
     *
     * @return the assistant's reply, which is the acknowledgement rather than the result — the
     *         work takes minutes, and the transcript fills in as it happens
     */
    @Transactional
    public ChatMessage say(UUID accountId, UUID threadId, String text) {
        ChatStore.Thread thread = require(accountId, threadId);
        if (thread.projectId() == null) {
            // The schema allows a project-less thread because a console may want one before the
            // user has committed to anything. Nothing can act on it: generation never creates a
            // project, since that is where the plan's project limit is enforced.
            throw new DroviException(com.pm.drovi_backend.common.ErrorCode.CONFLICT,
                    "This conversation is not attached to a sandbox yet.");
        }

        chats.append(threadId, ChatMessage.Role.USER, text);

        boolean built = !spec.listEndpoints(accountId, thread.projectId()).isEmpty();
        GenerationJob job = built
                ? generations.revise(accountId, thread.projectId(), text)
                : generations.start(accountId, thread.projectId(), text, null, null, true);

        String reply = built
                ? "Working on that change. I will tell you if anything is unclear."
                : waitMessage(accountId, thread.projectId());
        log.info("chat.said threadId={} jobId={} revise={}", threadId, job.id(), built);
        return chats.append(threadId, ChatMessage.Role.ASSISTANT, reply);
    }

    @Transactional(readOnly = true)
    public List<ChatStore.Thread> threads(UUID accountId) {
        return chats.threadsFor(accountId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messages(UUID accountId, UUID threadId) {
        require(accountId, threadId);
        return chats.messages(threadId);
    }

    /** The end goal's "wait for a defined time", said in the conversation rather than polled for. */
    private String waitMessage(UUID accountId, UUID projectId) {
        GenerationService.Progress progress = generations.progress(accountId, projectId);
        if (progress.estimatedSeconds() == null || progress.estimatedSeconds() == 0) {
            return "Building your sandbox.";
        }
        long minutes = Math.max(1, Math.round(progress.estimatedSeconds() / 60.0));
        return "Building your sandbox — about %d minute%s. I will ask if anything is ambiguous."
                .formatted(minutes, minutes == 1 ? "" : "s");
    }

    private ChatStore.Thread require(UUID accountId, UUID threadId) {
        return chats.find(accountId, threadId)
                .orElseThrow(() -> DroviException.notFound("No such conversation."));
    }
}
