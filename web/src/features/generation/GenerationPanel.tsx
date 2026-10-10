import { useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import type { BudgetResult } from "../../types/budget";
import type { PreviewInput } from "../budget/previewState";
import { inputKey } from "../budget/previewState";
import {
  canGenerate,
  type GenerationJob,
  type GenerationSettings,
  type GenerationStart,
} from "../../types/generation";

const errors: Record<string, string> = {
  GEMINI_AUTH_FAILED: "APIキーとモデルへのアクセス権を確認してください。",
  GEMINI_RATE_LIMITED:
    "利用制限に達しました。時間を置いて手動で再実行してください。",
  GENERATION_TIMEOUT:
    "生成がタイムアウトしました。自動再試行はしません。再実行は追加費用の可能性があります。",
  MODEL_UNSUPPORTED:
    "モデル名・generateContent・構造化出力対応を確認してください。",
  MODEL_LIMIT_EXCEEDED:
    "モデル固有の入力／出力上限を超えています。予算・選択・出力設定を変更してください。",
  GEMINI_BLOCKED:
    "安全制限により回答が生成されませんでした。質問と対象コードを確認してください。",
  OUTPUT_LIMIT:
    "出力上限に達したため不完全な回答を採用しませんでした。選択範囲や質問を絞ってください。",
  INVALID_ANSWER:
    "回答がJSON契約に適合しません。修復用の追加API呼び出しは行っていません。",
  FILE_CHANGED:
    "保存済みファイルが変更されています。再走査して概算を更新してください。",
  STALE_PREVIEW: "previewが古くなりました。概算を更新してください。",
  STALE_SETTINGS:
    "モデル／キー設定が変更されています。設定を確認し概算を更新してください。",
  BUSY: "別の生成が実行中です。完了を待ってください。",
  SECRET_REQUIRES_REVIEW:
    "秘密情報の疑いがあります。コードとpreviewを確認してください。",
};
export function generationError(code: string): string {
  return (
    errors[code] ??
    "生成操作に失敗しました。接続・設定・previewを確認してください。自動再送はしません。"
  );
}

/** 送信時の入力を固定する。編集は許可し、ポーリングでは生成APIを再送しない。 */
export function GenerationPanel({
  input,
  preview,
  busy,
}: {
  input: PreviewInput;
  preview?: BudgetResult;
  busy: boolean;
}) {
  const [settings, setSettings] = useState<GenerationSettings>();
  const [key, setKey] = useState("");
  const [saving, setSaving] = useState(false);
  const [sending, setSending] = useState(false);
  const [job, setJob] = useState<GenerationJob>();
  const [jobId, setJobId] = useState("");
  const [error, setError] = useState("");
  const pending = useRef<{ key: string; request: GenerationStart } | undefined>(
    undefined,
  );
  const sendingRef = useRef(false);
  const current = useRef({ input, preview, settings });
  current.current = { input, preview, settings };
  const running =
    sending ||
    (!!jobId && (!job || job.state === "QUEUED" || job.state === "GENERATING"));

  useEffect(() => {
    let cancelled = false;
    void Promise.all([
      api<GenerationSettings>("/settings"),
      api<{ jobId: string | null }>("/analysis/jobs/active"),
    ])
      .then(([value, active]) => {
        if (!cancelled) {
          setSettings(value);
          if (active.jobId) setJobId(active.jobId);
        }
      })
      .catch(() => {
        if (!cancelled)
          setError("設定状態を取得できません。Open Webで再接続してください。");
      });
    return () => {
      cancelled = true;
    };
  }, []);
  useEffect(() => {
    if (!jobId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const value = await api<GenerationJob>(`/jobs/${jobId}`);
        if (cancelled) return;
        setJob(value);
        if (value.state !== "QUEUED" && value.state !== "GENERATING") {
          pending.current = undefined;
          return;
        }
      } catch {
        if (!cancelled)
          setError(
            "結果取得に失敗しました。生成は再送せず、同じジョブの取得だけを再試行します。",
          );
      }
      if (!cancelled) timer = setTimeout(() => void poll(), 1000);
    }
    void poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [jobId]);

  async function configure(clear = false) {
    if (saving) return;
    setSaving(true);
    setError("");
    try {
      const value = clear
        ? await api<GenerationSettings>("/settings", undefined, "DELETE")
        : await api<GenerationSettings>(
            "/settings",
            { modelId: input.modelId, apiKey: key },
            "PUT",
          );
      setSettings(value);
    } catch {
      setError(
        "設定できません。モデルIDとキーの入力形式・接続を確認してください。",
      );
    } finally {
      setKey("");
      setSaving(false);
    }
  }

  /** 通信結果が不明な再送には同じrequestIdを使い、重複課金を防ぐ。 */
  async function send() {
    const fixed = current.current;
    if (
      sendingRef.current ||
      !canGenerate(fixed.input, fixed.preview, fixed.settings, running, busy)
    )
      return;
    const identity =
      inputKey(fixed.input) +
      fixed.preview!.previewId +
      fixed.settings!.version;
    if (pending.current?.key !== identity)
      pending.current = {
        key: identity,
        request: {
          previewId: fixed.preview!.previewId,
          requestId: crypto.randomUUID(),
          settingsVersion: fixed.settings!.version,
          input: {
            ...fixed.input,
            selectedFileIds: [...fixed.input.selectedFileIds],
            revision: fixed.preview!.revision,
          },
        },
      };
    sendingRef.current = true;
    setSending(true);
    setError("");
    try {
      const started = await api<{ jobId: string }>(
        "/analysis/jobs",
        pending.current.request,
      );
      setJob(undefined);
      setJobId(started.jobId);
    } catch (failure) {
      setError(
        generationError(failure instanceof Error ? failure.message : ""),
      );
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  }

  async function cancel() {
    try {
      setJob(await api<GenerationJob>(`/jobs/${jobId}/cancel`, {}));
    } catch {
      setError("キャンセルを確認できません。完了状態を確認してください。");
    }
  }
  return (
    <section>
      <h2>Geminiによる英語回答</h2>
      <p>
        実APIへの送信です。料金が発生する可能性があります。翻訳は後続Issueのため、今回は英語の構造化回答を表示します。
      </p>
      <p>
        previewのマスク・除外理由と送信全文を確認してください。秘密情報の完全な自動検出は保証しません。
      </p>
      <label>
        Gemini APIキー
        <input
          type="password"
          autoComplete="off"
          value={key}
          onChange={(event) => setKey(event.target.value)}
        />
      </label>
      <button
        disabled={!key || saving || running}
        onClick={() => void configure()}
      >
        現在のモデルIDとキーを設定
      </button>
      <button
        disabled={saving || running || !settings?.configured}
        onClick={() => void configure(true)}
      >
        設定解除
      </button>
      <p>
        {settings?.configured
          ? "キー設定済み（再表示・保存なし）"
          : "キー未設定"}
      </p>
      {settings?.configured && settings.modelId !== input.modelId && (
        <p>
          モデルが設定状態と異なります。現在のモデルとキーを設定してください。
        </p>
      )}
      <button
        disabled={
          !canGenerate(input, preview, settings, running || saving, busy)
        }
        onClick={() => void send()}
      >
        {running ? "生成中…" : "選定コードと質問をGeminiへ送信"}
      </button>
      {running && jobId && (
        <button onClick={() => void cancel()}>
          キャンセル（課金取消は保証しません）
        </button>
      )}
      {error && <p role="alert">{error}</p>}
      {job?.errorCode && (
        <p role="alert">
          {job.state === "CANCELLED"
            ? "キャンセルしました。実APIの課金取消は保証しません。"
            : generationError(job.errorCode)}
        </p>
      )}
      {job?.result && <EnglishResult result={job.result} />}
    </section>
  );
}

/** 後続翻訳用の英語DTOをHTMLとして解釈せず、安全な文字列とコードで表示する。 */
export function EnglishResult({
  result,
}: {
  result: NonNullable<GenerationJob["result"]>;
}) {
  const answer = result.answerEnglish;
  function references(ids: string[]) {
    return ids.map((id) => {
      const ref = result.references.find((item) => item.referenceId === id);
      return ref ? (
        <p key={id}>
          {ref.relativePath}：
          {ref.ranges
            .map((range) => `${range.beginLine}–${range.endLine}行`)
            .join(", ")}
        </p>
      ) : null;
    });
  }
  return (
    <article>
      <h3>英語回答（未翻訳）</h3>
      <p>実行時の質問：{result.question}</p>
      <p>
        生成対象snapshot：{result.snapshotId}
        。入力の編集はこの回答へ反映されません。
      </p>
      <p>{answer.summary}</p>
      {answer.sections.map((section, i) => (
        <section key={i}>
          <h4>{section.title}</h4>
          <p>{section.body}</p>
          {section.code && <pre>{section.code}</pre>}
          {references(section.referenceIds)}
        </section>
      ))}
      {answer.findings.map((finding, i) => (
        <section key={i}>
          <h4>
            {finding.severity}：{finding.title}
          </h4>
          <p>{finding.explanation}</p>
          <p>{finding.suggestion}</p>
          {references(finding.referenceIds)}
        </section>
      ))}
      <ul>
        {answer.limitations.map((text, i) => (
          <li key={i}>{text}</li>
        ))}
      </ul>
      <p>
        入力推定 {result.estimatedPromptTokens} tokens／API入力{" "}
        {result.usage.promptTokens ?? "未提供"}／出力{" "}
        {result.usage.outputTokens ?? "未提供"}／思考{" "}
        {result.usage.thoughtTokens ?? "未提供"}／cache{" "}
        {result.usage.cachedTokens ?? "未提供"}／total{" "}
        {result.usage.totalTokens ?? "未提供"}／生成 {result.generationMs} ms
      </p>
      {result.warnings.map((warning, i) => (
        <p key={i}>{warning}</p>
      ))}
    </article>
  );
}
