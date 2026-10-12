import { describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  ConversationPanel,
  readConversationFile,
  createNewConversation,
} from "./ConversationPanel";
import { ApiFailure, type api } from "../../api/client";
import { inputKey } from "../budget/previewState";
import type { PreviewInput } from "../budget/previewState";

describe("conversation TXT", () => {
  it("creates a new conversation after expired DELETE without repeating a generation", async () => {
    const next = {
      conversationId: "new",
      workspaceId: "w",
      version: 0,
      turns: [],
      imported: null,
    };
    const client = vi
      .fn()
      .mockRejectedValueOnce(new ApiFailure(404, "CONVERSATION_NOT_FOUND"))
      .mockResolvedValueOnce(next);
    expect(
      await createNewConversation("w", "expired", client as typeof api),
    ).toEqual(next);
    expect(client.mock.calls).toEqual([
      ["/conversations/expired", undefined, "DELETE"],
      ["/conversations", { workspaceId: "w" }],
    ]);
  });
  it("does not treat authentication or network failure as successful deletion", async () => {
    const client = vi
      .fn()
      .mockRejectedValue(new ApiFailure(401, "INVALID_CONNECTION"));
    await expect(
      createNewConversation("w", "old", client as typeof api),
    ).rejects.toThrow("INVALID_CONNECTION");
    expect(client).toHaveBeenCalledTimes(1);
  });
  it("keeps saving disabled without committed history and renders import confirmation", () => {
    const html = renderToStaticMarkup(
      <ConversationPanel workspaceId="w" refresh={0} onChange={() => {}} />,
    );
    expect(html).toContain("disabled");
    expect(html).toContain("会話をTXT保存");
    expect(html).toContain("引継ぎをプレビュー");
    expect(html).toContain("秘密情報");
  });
  it("displays committed questions and untranslated answer without interpreting HTML", () => {
    const html = renderToStaticMarkup(
      <ConversationPanel
        workspaceId="w"
        refresh={0}
        onChange={() => {}}
        view={{
          conversationId: "c",
          workspaceId: "w",
          version: 1,
          turns: [
            {
              jobId: "j",
              question: "日本語質問",
              displayText: "未翻訳：英語原文\n<script>answer</script>",
            },
          ],
          imported: null,
        }}
      />,
    );
    expect(html).toContain("日本語質問");
    expect(html).toContain("未翻訳");
    expect(html).toContain("&lt;script&gt;");
  });
  it("rejects malformed UTF8 and oversized files before requests", async () => {
    await expect(
      readConversationFile(new File([new Uint8Array([0xc3, 0x28])], "bad.txt")),
    ).rejects.toThrow("UTF-8");
    await expect(
      readConversationFile(new File([new Uint8Array(1_048_577)], "large.txt")),
    ).rejects.toThrow("1MiB");
    expect(
      await readConversationFile(
        new File(["\uFEFF日本語😀\r\nUSER:"], "valid.txt"),
      ),
    ).toBe("日本語😀\r\nUSER:");
  });
  it("invalidates estimates when conversation identity or version changes", () => {
    const input: PreviewInput = {
      workspaceId: "w",
      snapshotId: "s",
      question: "質問",
      mode: "SERVICE_REVIEW",
      selectedFileIds: ["f"],
      inputBudgetTokens: 8192,
      modelId: "m",
      targetFileId: null,
      targetMethodId: null,
      conversationId: "c",
      conversationVersion: 1,
    };
    expect(inputKey({ ...input, conversationVersion: 2 })).not.toBe(
      inputKey(input),
    );
    expect(inputKey({ ...input, conversationId: "other" })).not.toBe(
      inputKey(input),
    );
    expect(inputKey({ ...input, snapshotId: "new" })).not.toBe(inputKey(input));
  });
});
