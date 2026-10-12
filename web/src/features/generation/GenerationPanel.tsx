import { useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import { apiErrorMessage } from "../../api/errorPresentation";
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
  MODEL_NOT_FOUND:
    "指定モデルがAPI v1betaで見つかりません。モデルID、利用権限、廃止・バージョン互換性を確認してください。廃止かどうかはこの応答だけでは断定できません。",
  GEMINI_INVALID_REQUEST:
    "モデルまたはAPIバージョンが要求形式・構造化出力に対応しないか、パラメーターが不正です。入力と対応機能を確認してください。原因はこの応答だけでは断定できません。",
  GEMINI_PRECONDITION_FAILED:
    "APIの利用前提を満たしていません。課金・地域・プロジェクトの利用設定を確認してください。",
  GEMINI_QUOTA_EXCEEDED:
    "APIの利用枠を超えています。時間単位・日単位の割当とプロジェクト設定を確認してください。自動再試行はしません。",
  GEMINI_BILLING_REQUIRED:
    "APIの支払い条件を満たしていません。残高・課金設定を確認してください。",
  GEMINI_SERVER_ERROR: "API側の内部エラーです。自動再試行はしません。",
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

export function sourceLabel(source: string | undefined): string {
  if (source === "STUB")
    return "固定応答（外部API呼出なし・実測値ではありません）";
  if (source === "DEMO")
    return "デモ用認証による実API（料金が発生する可能性があります）";
  return "ユーザー認証による実API（料金が発生する可能性があります）";
}

/** 英語確定と翻訳表示更新を区別し、翻訳中でも確定済みTXTを取得できるようにする。 */
export function conversationRefreshKey(job: GenerationJob): string {
  return job.result
    ? `${job.jobId}:${job.state}:${job.translation?.attempt ?? 0}:${job.translation?.status ?? "none"}`
    : "";
}

export function TranslationSettingsStatus({
  ready,
}: {
  ready: boolean | undefined;
}) {
  return (
    <p>
      {ready === true
        ? "翻訳認証設定あり（有効性・疎通は未確認）"
        : ready === false
          ? "現在は英語回答。日本語翻訳は未設定。会話履歴とTXT保存は英語回答でも利用できます。"
          : "翻訳設定の状態は未確認です。"}
    </p>
  );
}

/** 外部のエラー本文を表示せず、定型コードから次の操作を示す。 */
export function translationError(code: string): string {
  const messages: Record<string, string> = {
    TRANSLATION_NOT_READY:
      "翻訳未設定です。GCP認証とprojectを設定してください。英語原文を表示します。",
    TRANSLATION_AUTH_FAILED:
      "翻訳認証に失敗しました。認証ファイル・権限・API有効化を確認してください。",
    TRANSLATION_TIMEOUT:
      "翻訳が時間上限に達しました。英語原文を保持しています。",
    TRANSLATION_CANCELLED:
      "翻訳を中止しました。取得済み回答とusageは保持しています。",
    TRANSLATION_QUOTA_EXCEEDED:
      "翻訳の利用上限に達しました。割当・課金設定を確認してください。",
    TRANSLATION_PLACEHOLDER_MISMATCH:
      "コード識別子を安全に復元できなかったため、この部分は英語原文を表示します。",
    TRANSLATION_TEXT_TOO_LONG:
      "説明が翻訳上限を超え、安全に分割できないため英語原文を表示します。",
  };
  return (
    messages[code] ??
    "翻訳に失敗した部分は英語原文を表示します。再翻訳してもGeminiは再実行しません。"
  );
}

/** 外部の任意文字列は表示せず、定型値だけで失敗箇所と制限を説明する。 */
export function generationDiagnostics(
  details: GenerationJob["errorDetails"],
): string {
  if (!details) return "";
  const stages = {
    MODEL_INFO: "モデル情報取得",
    GENERATE: "回答生成",
    JOB: "生成ジョブ",
  };
  const limits = {
    PER_MINUTE: "分単位の利用上限",
    PER_DAY: "日単位の利用上限",
    MULTIPLE: "分・日単位の利用上限",
    UNKNOWN: "制限の内訳は未特定",
  };
  const statuses = new Set([
    "INVALID_ARGUMENT",
    "FAILED_PRECONDITION",
    "PERMISSION_DENIED",
    "UNAUTHENTICATED",
    "NOT_FOUND",
    "RESOURCE_EXHAUSTED",
    "UNAVAILABLE",
    "INTERNAL",
    "DEADLINE_EXCEEDED",
    "UNIMPLEMENTED",
    "UNKNOWN",
  ]);
  const http =
    typeof details.httpStatus === "number" &&
    Number.isInteger(details.httpStatus) &&
    details.httpStatus >= 100 &&
    details.httpStatus <= 599
      ? details.httpStatus
      : "未特定";
  const status = statuses.has(details.apiStatus)
    ? details.apiStatus
    : "UNKNOWN";
  const timeouts = {
    CONNECT: "接続タイムアウト",
    RESPONSE_WAIT: "応答待ちタイムアウト（ヘッダー・本文の待機を含む）",
    JOB_DEADLINE: "ジョブ期限超過",
    NONE: "",
  };
  const timeout = details.timeoutType ? timeouts[details.timeoutType] : "";
  const elapsed =
    typeof details.elapsedMs === "number"
      ? `経過 ${details.elapsedMs} ms。`
      : "";
  return `${stages[details.stage] ?? "失敗箇所は未特定"}：HTTP ${http} / ${status}。${limits[details.limitType] ?? "制限の内訳は未特定"}。${timeout ? `${timeout}。` : ""}${elapsed}`;
}

/** 送信時の入力を固定する。編集は許可し、ポーリングでは生成APIを再送しない。 */
export function GenerationPanel({
  input,
  preview,
  busy,
  onModelConfigured,
  onCompleted,
}: {
  input: PreviewInput;
  preview?: BudgetResult;
  busy: boolean;
  onModelConfigured?: (modelId: string) => void;
  onCompleted?: () => void;
}) {
  const [settings, setSettings] = useState<GenerationSettings>();
  const [key, setKey] = useState("");
  const [saving, setSaving] = useState(false);
  const [sending, setSending] = useState(false);
  const [job, setJob] = useState<GenerationJob>();
  const [jobId, setJobId] = useState("");
  const [error, setError] = useState("");
  const [translationReady, setTranslationReady] = useState<boolean>();
  const [translationRetry, setTranslationRetry] = useState(0);
  const pending = useRef<{ key: string; request: GenerationStart } | undefined>(
    undefined,
  );
  const sendingRef = useRef(false);
  const conversationNotification = useRef("");
  const settingsRequestVersion = useRef(0);
  const current = useRef({ input, preview, settings });
  current.current = { input, preview, settings };
  const running =
    sending ||
    (!!jobId &&
      (!job ||
        job.state === "QUEUED" ||
        job.state === "GENERATING" ||
        job.state === "TRANSLATING" ||
        job.translation?.status === "RUNNING"));

  useEffect(() => {
    let stopped = false;
    void api<{ configured: boolean }>("/translation/status")
      .then((value) => {
        if (!stopped) setTranslationReady(value.configured);
      })
      .catch(() => {
        if (!stopped) setTranslationReady(undefined);
      });
    return () => {
      stopped = true;
    };
  }, []);
  useEffect(() => {
    let cancelled = false;
    const version = settingsRequestVersion.current;
    void Promise.all([
      api<GenerationSettings>("/settings"),
      api<{ jobId: string | null }>("/analysis/jobs/active"),
    ])
      .then(([value, active]) => {
        if (!cancelled) {
          // 初期GETの遅い応答で、その後の設定成功・失敗の表示を上書きしない。
          if (version === settingsRequestVersion.current) setSettings(value);
          if (active.jobId) setJobId(active.jobId);
        }
      })
      .catch((failure: unknown) => {
        if (!cancelled && version === settingsRequestVersion.current)
          setError("設定状態の取得に失敗しました。" + apiErrorMessage(failure));
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
        const notification = conversationRefreshKey(value);
        if (notification && notification !== conversationNotification.current) {
          conversationNotification.current = notification;
          onCompleted?.();
        }
        if (
          value.state !== "QUEUED" &&
          value.state !== "GENERATING" &&
          value.state !== "TRANSLATING" &&
          value.translation?.status !== "RUNNING"
        ) {
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
  }, [jobId, translationRetry]);

  async function configure(clear = false) {
    if (saving) return;
    settingsRequestVersion.current += 1;
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
    } catch (failure) {
      // 通信結果が不明な場合も、最後の表示を現在の設定成功として扱わない。
      setSettings(undefined);
      setError(
        "設定操作に失敗しました。安全のため入力したキーは消去しました。" +
          apiErrorMessage(failure),
      );
    } finally {
      setKey("");
      setSaving(false);
    }
  }

  /** 認証値をWebへ取り出さず、明示操作でデモ／固定応答の設定を有効にする。 */
  async function activateSource(source: "demo" | "stub") {
    if (saving || running) return;
    settingsRequestVersion.current += 1;
    setSaving(true);
    setError("");
    try {
      const value = await api<GenerationSettings>(`/settings/${source}`, {});
      setSettings(value);
      onModelConfigured?.(value.modelId);
    } catch (failure) {
      setSettings(undefined);
      setError("設定操作に失敗しました。" + apiErrorMessage(failure));
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

  /** 取得済み回答だけを再翻訳し、生成POSTは送らない。 */
  async function retryTranslation() {
    if (!jobId || running) return;
    setSending(true);
    try {
      await api<{ jobId: string }>(`/jobs/${jobId}/translate`, {});
      setJob(await api<GenerationJob>(`/jobs/${jobId}`));
      setTranslationRetry((value) => value + 1);
    } catch (failure) {
      setError("再翻訳を開始できません。" + apiErrorMessage(failure));
    } finally {
      setSending(false);
    }
  }
  return (
    <section>
      <h2>Geminiの英語回答と日本語翻訳</h2>
      <p>
        実APIモードでは生成・翻訳に料金が発生する可能性があります。翻訳できない部分は英語原文を表示します。固定応答モードは外部APIを呼びません。
      </p>
      <TranslationSettingsStatus ready={translationReady} />
      <p>
        previewのマスク・除外理由と送信全文を確認してください。秘密情報の完全な自動検出は保証しません。
      </p>
      {settings?.configured && <p>{sourceLabel(settings.source)}</p>}
      {settings?.demoAvailable && (
        <button
          disabled={saving || running}
          onClick={() => void activateSource("demo")}
        >
          ローカルのデモ認証を利用（実API）
        </button>
      )}
      {settings?.stubAvailable && (
        <button
          disabled={saving || running}
          onClick={() => void activateSource("stub")}
        >
          固定応答を有効化（キー不要）
        </button>
      )}
      {!settings?.stubAvailable && (
        <label>
          Gemini APIキー
          <input
            type="password"
            autoComplete="off"
            value={key}
            onChange={(event) => setKey(event.target.value)}
          />
        </label>
      )}
      <button
        disabled={!key || saving || running || settings?.stubAvailable}
        onClick={() => void configure()}
      >
        現在のモデルIDとキーを設定
      </button>
      <button
        disabled={saving || running || settings?.configured === false}
        onClick={() => void configure(true)}
      >
        設定解除
      </button>
      <p>
        {settings?.configured
          ? settings.source === "STUB"
            ? `固定応答設定済み（モデルID: ${settings.modelId}）`
            : `キー設定済み（モデルID: ${settings.modelId}、Gemini疎通は未確認）`
          : settings
            ? "キー未設定"
            : "キー設定状態を確認できません（未設定とは限りません）"}
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
        {running
          ? "生成中…"
          : settings?.stubAvailable
            ? "選定コードと質問で固定応答を検証"
            : "選定コードと質問をGeminiへ送信"}
      </button>
      {running && jobId && (
        <button onClick={() => void cancel()}>
          {job?.state === "TRANSLATING" ||
          job?.translation?.status === "RUNNING"
            ? "翻訳だけ中止（回答は保持、課金取消は保証しません）"
            : "キャンセル（課金取消は保証しません）"}
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
      {job?.errorDetails && <p>{generationDiagnostics(job.errorDetails)}</p>}
      {job?.result && (
        <>
          <EnglishResult result={job.result} translation={job.translation} />
          {job.translation && job.translation.status !== "SUCCEEDED" && (
            <button disabled={running} onClick={() => void retryTranslation()}>
              再翻訳（Geminiは再実行しません）
            </button>
          )}
        </>
      )}
    </section>
  );
}

/** 後続翻訳用の英語DTOをHTMLとして解釈せず、安全な文字列とコードで表示する。 */
export function EnglishResult({
  result,
  translation,
}: {
  result: NonNullable<GenerationJob["result"]>;
  translation?: GenerationJob["translation"];
}) {
  const [showEnglish, setShowEnglish] = useState(false);
  const answer =
    !showEnglish && translation
      ? translation.displayAnswer
      : result.answerEnglish;
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
      <h3>
        {!showEnglish && translation?.status === "SUCCEEDED"
          ? "日本語回答"
          : !showEnglish && translation?.partialTranslation
            ? "日本語回答（一部は英語原文）"
            : "英語回答（未翻訳）"}
      </h3>
      {translation && (
        <button onClick={() => setShowEnglish((value) => !value)}>
          {showEnglish ? "日本語表示へ" : "英語原文を表示"}
        </button>
      )}
      {translation?.status === "RUNNING" && (
        <p>翻訳中です。取得済み回答は保持されています。</p>
      )}
      {translation && (
        <p>
          翻訳試行 {translation.attempt}回／{translation.durationMs} ms
        </p>
      )}
      {translation?.warnings.map((warning, index) => (
        <p key={index} role="alert">
          {translationError(warning.code)} 未翻訳箇所：{warning.field}
        </p>
      ))}
      <p>実行時の質問：{result.question}</p>
      <p>{sourceLabel(result.source)}</p>
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
