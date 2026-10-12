import { useEffect, useRef, useState } from "react";
import { api, ApiFailure } from "../../api/client";
import { apiErrorMessage } from "../../api/errorPresentation";

export interface ConversationView {
  conversationId: string;
  workspaceId: string;
  version: number;
  turns: { jobId: string; question: string; displayText: string }[];
  imported: {
    type: "NOTE" | "TRANSCRIPT";
    note: string | null;
    turns: unknown[];
  } | null;
}
interface ImportPreview {
  importId: string;
  type: "NOTE" | "TRANSCRIPT";
  turns: number;
  codePoints: number;
  estimatedTokens: number;
  warnings: string[];
}
const MAX_BYTES = 1_048_576;

/** 期限切れ・破棄済みだけを復帰可能とし、通信・認証障害を成功扱いしない。 */
export async function createNewConversation(
  workspaceId: string,
  conversationId: string,
  client: typeof api = api,
): Promise<ConversationView> {
  try {
    await client(`/conversations/${conversationId}`, undefined, "DELETE");
  } catch (failure) {
    if (
      !(failure instanceof ApiFailure) ||
      failure.status !== 404 ||
      failure.code !== "CONVERSATION_NOT_FOUND"
    )
      throw failure;
  }
  return client<ConversationView>("/conversations", { workspaceId });
}

/** ファイルは厳密UTF-8で読み、任意ローカルパスをバックエンドへ渡さない。 */
export async function readConversationFile(file: File): Promise<string> {
  if (file.size > MAX_BYTES)
    throw new Error("1MiB以内のTXTを選択してください。");
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(
      await file.arrayBuffer(),
    );
  } catch {
    throw new Error(
      "UTF-8のTXTとして読み込めません。元ファイルを確認してください。",
    );
  }
}

/** 明示確認後に新会話へ切り替え、取込失敗時は入力した原文を保持する。 */
export function ConversationPanel({
  workspaceId,
  view,
  onChange,
  refresh,
}: {
  workspaceId: string;
  view?: ConversationView;
  onChange: (view: ConversationView) => void;
  refresh: number;
}) {
  const [text, setText] = useState("");
  const [kind, setKind] = useState<"FILE" | "PASTE">("PASTE");
  const [preview, setPreview] = useState<ImportPreview>();
  const [error, setError] = useState("");
  const [working, setWorking] = useState(false);
  const inputVersion = useRef(0);

  useEffect(() => {
    let cancelled = false;
    void api<ConversationView>("/conversations", { workspaceId })
      .then((value) => {
        if (!cancelled) onChange(value);
      })
      .catch((failure) => {
        if (!cancelled) setError(apiErrorMessage(failure));
      });
    return () => {
      cancelled = true;
    };
    // workspace切替で新会話。モード・snapshot変更では会話は保持しpreviewだけ再計算する。
  }, [workspaceId]);

  useEffect(() => {
    if (!view || refresh === 0) return;
    let cancelled = false;
    void api<ConversationView>(`/conversations/${view.conversationId}`)
      .then((value) => {
        if (!cancelled) onChange(value);
      })
      .catch((failure) => {
        if (!cancelled) setError(apiErrorMessage(failure));
      });
    return () => {
      cancelled = true;
    };
  }, [refresh, view?.conversationId]);

  async function action(task: () => Promise<void>) {
    if (working) return;
    setWorking(true);
    setError("");
    try {
      await task();
    } catch (failure) {
      setError(apiErrorMessage(failure));
    } finally {
      setWorking(false);
    }
  }

  async function download() {
    if (!view) return;
    const result = await api<string>(
      `/conversations/${view.conversationId}/export?format=txt`,
      undefined,
      undefined,
      "text",
    );
    const url = URL.createObjectURL(
      new Blob([result.startsWith("\uFEFF") ? result : "\uFEFF" + result], {
        type: "text/plain;charset=utf-8",
      }),
    );
    const anchor = document.createElement("a");
    const stamp = new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Tokyo",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    })
      .format(new Date())
      .replace(/[-: ]/g, "")
      .replace("T", "_");
    anchor.href = url;
    anchor.download = `cheapreview_conversation_${stamp.slice(0, 8)}_${stamp.slice(8)}.txt`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  const hasHistory = !!view && (view.turns.length > 0 || !!view.imported);
  return (
    <section>
      <h2>会話とTXT引継ぎ</h2>
      <p>
        会話はメモリ保持・最大20往復です。TXTには表示回答を保存します。秘密情報を質問や取込本文へ含めないでください。
      </p>
      <button
        disabled={!hasHistory || working}
        onClick={() => void action(download)}
      >
        会話をTXT保存
      </button>
      <button
        disabled={working || !view}
        onClick={() =>
          void action(async () => {
            if (
              !window.confirm("現在の会話を破棄して新しい会話を開始しますか？")
            )
              return;
            onChange(
              await createNewConversation(workspaceId, view!.conversationId),
            );
          })
        }
      >
        新しい会話
      </button>
      {view?.turns.map((turn) => (
        <article key={turn.jobId}>
          <h3>質問</h3>
          <p>{turn.question}</p>
          <pre>{turn.displayText}</pre>
        </article>
      ))}
      {view?.imported && (
        <p>
          引継ぎ資料：
          {view.imported.type === "NOTE"
            ? "メモ"
            : `${view.imported.turns.length}往復`}
          。旧snapshotを復元せず今回のコードを根拠にします。
        </p>
      )}
      <details>
        <summary>会話を引き継ぐ（TXT・貼り付け）</summary>
        <label>
          UTF-8 TXT（最大1MiB）
          <input
            type="file"
            accept=".txt,text/plain"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (!file) return;
              const startedVersion = ++inputVersion.current;
              void readConversationFile(file)
                .then((value) => {
                  if (startedVersion !== inputVersion.current) return;
                  setText(value);
                  setKind("FILE");
                  setPreview(undefined);
                  setError("");
                })
                .catch((failure) =>
                  setError(
                    failure instanceof Error
                      ? failure.message
                      : "ファイル読込に失敗しました。",
                  ),
                );
            }}
          />
        </label>
        <label>
          引継ぎ本文（最大100,000文字）
          <textarea
            value={text}
            onChange={(event) => {
              inputVersion.current++;
              setText(event.target.value);
              setKind("PASTE");
              setPreview(undefined);
            }}
          />
        </label>
        <button
          disabled={working || !text.trim()}
          onClick={() =>
            void action(async () => {
              const startedVersion = inputVersion.current;
              const result = await api<ImportPreview>(
                "/conversation-imports/preview",
                {
                  text,
                  inputKind: kind,
                },
              );
              if (startedVersion === inputVersion.current) setPreview(result);
            })
          }
        >
          引継ぎをプレビュー
        </button>
        {preview && (
          <div>
            <p>
              {preview.type} · {preview.turns}往復 · {preview.codePoints}文字 ·
              見込み{preview.estimatedTokens} tokens
            </p>
            {preview.warnings.map((warning) => (
              <p key={warning}>{warning}</p>
            ))}
            <button
              disabled={working}
              onClick={() =>
                void action(async () => {
                  if (
                    !window.confirm(
                      "現在の会話を上書きせず、この履歴を使う新しい会話へ切り替えますか？",
                    )
                  )
                    return;
                  onChange(
                    await api<ConversationView>("/conversations/import", {
                      workspaceId,
                      importId: preview.importId,
                    }),
                  );
                  setPreview(undefined);
                  setText("");
                })
              }
            >
              この履歴で開始
            </button>
          </div>
        )}
      </details>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
