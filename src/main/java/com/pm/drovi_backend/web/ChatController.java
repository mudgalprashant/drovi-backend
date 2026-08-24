package com.pm.drovi_backend.web;

import com.pm.drovi_backend.chat.ChatMessage;
import com.pm.drovi_backend.chat.ChatService;
import com.pm.drovi_backend.chat.ChatStore;
import com.pm.drovi_backend.identity.DroviPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Chat: one place to say what you want, whatever stage the sandbox is at.
 *
 * <p>The same sentence means different things depending on the project — against an empty one it
 * builds, against a built one it changes the data. Deciding that here rather than making the user
 * choose an endpoint is the point of this surface.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class ChatController {

    private final ChatService chat;

    record StartThreadRequest(@Size(max = 200) String title) {
    }

    record SayRequest(@NotBlank @Size(max = 4000) String message) {
    }

    record ThreadResponse(String id, String projectId, String title, String updatedAt) {
    }

    record MessageResponse(String id, int seq, String role, String content, String createdAt) {
    }

    @PostMapping("/projects/{projectId}/threads")
    @ResponseStatus(HttpStatus.CREATED)
    ThreadResponse start(@AuthenticationPrincipal DroviPrincipal caller,
                         @PathVariable UUID projectId,
                         @Valid @RequestBody StartThreadRequest request) {
        return toResponse(chat.start(caller.accountId(), projectId, request.title()));
    }

    @GetMapping("/threads")
    List<ThreadResponse> threads(@AuthenticationPrincipal DroviPrincipal caller) {
        return chat.threads(caller.accountId()).stream().map(ChatController::toResponse).toList();
    }

    /**
     * Returns the assistant's <em>acknowledgement</em>, not the result. The work takes minutes;
     * the transcript fills in as it happens, and a request that waited for it would hold a
     * connection for all of them.
     */
    @PostMapping("/threads/{threadId}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    MessageResponse say(@AuthenticationPrincipal DroviPrincipal caller,
                        @PathVariable UUID threadId,
                        @Valid @RequestBody SayRequest request) {
        return toResponse(chat.say(caller.accountId(), threadId, request.message()));
    }

    @GetMapping("/threads/{threadId}/messages")
    List<MessageResponse> messages(@AuthenticationPrincipal DroviPrincipal caller,
                                   @PathVariable UUID threadId) {
        return chat.messages(caller.accountId(), threadId).stream()
                .map(ChatController::toResponse).toList();
    }

    private static ThreadResponse toResponse(ChatStore.Thread thread) {
        return new ThreadResponse(thread.id().toString(),
                thread.projectId() == null ? null : thread.projectId().toString(),
                thread.title(), thread.updatedAt() == null ? null : thread.updatedAt().toString());
    }

    private static MessageResponse toResponse(ChatMessage message) {
        return new MessageResponse(message.id().toString(), message.seq(), message.role().name(),
                message.content(), message.createdAt() == null ? null : message.createdAt().toString());
    }
}
