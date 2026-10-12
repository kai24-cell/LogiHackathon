package jp.cheapreview.conversation.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import jp.cheapreview.conversation.dto.ConversationDtos;
import jp.cheapreview.conversation.service.ConversationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ConversationController {
  private final ConversationService conversations;

  public ConversationController(ConversationService conversations) {
    this.conversations = conversations;
  }

  @PostMapping("/conversations")
  public ConversationDtos.View create(@RequestBody ConversationDtos.Create request) {
    return conversations.create(request.workspaceId());
  }

  @GetMapping("/conversations/{id}")
  public ConversationDtos.View get(@PathVariable String id) {
    return conversations.get(id);
  }

  @DeleteMapping("/conversations/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    conversations.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/conversation-imports/preview")
  public ConversationDtos.ImportPreview preview(
      @RequestBody ConversationDtos.PreviewRequest request) {
    return conversations.preview(request);
  }

  @PostMapping("/conversations/import")
  public ConversationDtos.View importConversation(
      @RequestBody ConversationDtos.ImportRequest request) {
    return conversations.importConversation(request.workspaceId(), request.importId());
  }

  @GetMapping(value = "/conversations/{id}/export", produces = "text/plain;charset=UTF-8")
  public ResponseEntity<byte[]> export(@PathVariable String id) {
    String stamp =
        LocalDateTime.now(ZoneId.of("Asia/Tokyo"))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"cheapreview_conversation_" + stamp + ".txt\"")
        .body(conversations.export(id).getBytes(StandardCharsets.UTF_8));
  }
}
