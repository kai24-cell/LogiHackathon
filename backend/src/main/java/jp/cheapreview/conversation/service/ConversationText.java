package jp.cheapreview.conversation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jp.cheapreview.conversation.dto.ConversationDtos.Imported;
import jp.cheapreview.conversation.dto.ConversationDtos.TextTurn;
import jp.cheapreview.conversation.dto.ConversationDtos.Type;
import jp.cheapreview.generation.dto.GenerationDtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 画面に表示する回答だけをTXTへ変換し、内部promptや添付コードを参照しない。 */
public final class ConversationText {
  public static final String HEADER = "CHEAPREVIEW_CHAT_V1";
  public static final String NOTE = "引継ぎメモ（ユーザー取込）\n";

  private ConversationText() {}

  /** 自アプリのTXT参照行だけを識別し、任意文章をパスとして読み込まない。 */
  public static java.util.Set<String> referencePaths(Imported imported) {
    if (imported == null) return java.util.Set.of();
    var result = new java.util.LinkedHashSet<String>();
    var texts = new java.util.ArrayList<String>();
    if (imported.note() != null) texts.add(imported.note());
    for (var turn : imported.turns()) {
      texts.add(turn.question());
      texts.add(turn.answer());
    }
    var pattern =
        java.util.regex.Pattern.compile(
            "(?m)^ref-[1-9][0-9]*: (.+?) [0-9]+–[0-9]+行(?:, [0-9]+–[0-9]+行)*$");
    for (String text : texts) {
      var matcher = pattern.matcher(text);
      while (matcher.find()) result.add(matcher.group(1));
    }
    return java.util.Set.copyOf(result);
  }

  public static String answer(
      GenerationDtos.Answer answer, List<GenerationDtos.Reference> refs, boolean untranslated) {
    var text = new StringBuilder();
    if (untranslated) text.append("未翻訳：英語原文\n");
    text.append(answer.summary()).append("\n");
    for (var section : answer.sections()) {
      text.append("\n").append(section.title()).append("\n").append(section.body()).append("\n");
      if (section.code() != null && !section.code().isEmpty())
        text.append("```\n").append(section.code()).append("\n```\n");
    }
    for (var finding : answer.findings())
      text.append("\n[")
          .append(finding.severity())
          .append("] ")
          .append(finding.title())
          .append("\n")
          .append(finding.explanation())
          .append("\n")
          .append(finding.suggestion())
          .append("\n");
    for (var ref : refs)
      text.append("\n")
          .append(ref.referenceId())
          .append(": ")
          .append(ref.relativePath())
          .append(" ")
          .append(
              ref.ranges().stream()
                  .map(range -> range.beginLine() + "–" + range.endLine() + "行")
                  .collect(java.util.stream.Collectors.joining(", ")))
          .append("\n");
    for (var limitation : answer.limitations())
      text.append("\n制限: ").append(limitation).append("\n");
    return text.toString();
  }

  /** 本文の区切り行と先頭backslashを一段だけescapeし、末尾改行も往復で保持する。 */
  public static String export(Imported imported, List<TextTurn> turns, Instant now) {
    var out = new StringBuilder("\uFEFF" + HEADER + "\nEXPORTED_AT=" + now + "\n");
    if (imported != null) {
      if (imported.type() == Type.NOTE) append(out, "USER:", NOTE + imported.note());
      for (var turn : imported.turns()) appendTurn(out, turn);
    }
    for (var turn : turns) appendTurn(out, turn);
    return out.toString();
  }

  private static void appendTurn(StringBuilder out, TextTurn turn) {
    append(out, "USER:", turn.question());
    append(out, "ASSISTANT:", turn.answer());
  }

  private static void append(StringBuilder out, String role, String value) {
    out.append(role).append("\n");
    for (String line : value.split("\n", -1)) {
      if (line.equals("USER:") || line.equals("ASSISTANT:") || line.startsWith("\\"))
        out.append("\\");
      out.append(line).append("\n");
    }
  }

  /** 専用headerを名乗る不正TXTはNOTEに降格せず拒否し、任意文章には役割を推測しない。 */
  public static Imported parse(String input) {
    String text = input.startsWith("\uFEFF") ? input.substring(1) : input;
    text = text.replace("\r\n", "\n");
    if (text.isBlank()) invalid();
    if (!text.startsWith(HEADER)) return new Imported(Type.NOTE, text, List.of());
    var lines = text.split("\n", -1);
    if (lines.length < 4 || !lines[0].equals(HEADER) || !lines[1].startsWith("EXPORTED_AT="))
      invalid();
    try {
      Instant.parse(lines[1].substring("EXPORTED_AT=".length()));
    } catch (Exception failure) {
      invalid();
    }
    List<String> roles = new ArrayList<>();
    List<String> values = new ArrayList<>();
    StringBuilder value = null;
    for (int index = 2; index < lines.length; index++) {
      String line = lines[index];
      if (line.equals("USER:") || line.equals("ASSISTANT:")) {
        if (value != null) values.add(finish(value));
        roles.add(line);
        value = new StringBuilder();
      } else {
        if (index == lines.length - 1 && line.isEmpty()) continue;
        if (value == null) invalid();
        if (line.startsWith("\\")
            && !line.startsWith("\\\\")
            && !line.equals("\\USER:")
            && !line.equals("\\ASSISTANT:")) invalid();
        value.append(line.startsWith("\\") ? line.substring(1) : line).append("\n");
      }
    }
    if (value != null) values.add(finish(value));
    List<TextTurn> turns = new ArrayList<>();
    String note = null;
    int index = 0;
    // NOTEは先頭の独立USER記録のみ。後続の通常往復は順序を厳密に検証する。
    if (!values.isEmpty()
        && roles.get(0).equals("USER:")
        && values.get(0).startsWith(NOTE)
        && (roles.size() == 1 || roles.get(1).equals("USER:"))) {
      note = values.get(0).substring(NOTE.length());
      index = 1;
    }
    while (index < roles.size()) {
      if (index + 1 >= roles.size()
          || !roles.get(index).equals("USER:")
          || !roles.get(index + 1).equals("ASSISTANT:")) invalid();
      turns.add(new TextTurn(values.get(index), values.get(index + 1)));
      index += 2;
    }
    if (turns.size() > 20) invalid();
    if (note != null) return new Imported(Type.NOTE, note, List.copyOf(turns));
    if (turns.isEmpty() || turns.size() > 20) invalid();
    return new Imported(Type.TRANSCRIPT, null, List.copyOf(turns));
  }

  private static String finish(StringBuilder value) {
    if (value.length() == 0) {
      invalid();
    }
    String result = value.substring(0, value.length() - 1);
    if (result.isBlank()) invalid();
    return result;
  }

  private static void invalid() {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT");
  }
}
