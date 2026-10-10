import { useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import type { BudgetResult } from "../../types/budget";
import type { SearchMode } from "../../types/search";
import {
  currentResult,
  inputKey,
  PreviewRevisions,
  validInput,
  validationIsCurrent,
} from "./previewState";
import type { PreviewInput } from "./previewState";

const PREVIEW_DELAY_MS = 300;

/** 入力変更をdebounceし、計算中・期限切れ・入力違いの結果では確認操作を許可しない。 */
export function BudgetPanel({
  workspaceId,
  snapshotId,
  question,
  mode,
  selected,
  busy,
}: {
  workspaceId: string;
  snapshotId: string;
  question: string;
  mode: SearchMode;
  selected: ReadonlySet<string>;
  busy: boolean;
}) {
  const [budget, setBudget] = useState("8192");
  const [modelId, setModelId] = useState("unconfigured-model");
  const [targetFile, setTargetFile] = useState("");
  const [targetMethod, setTargetMethod] = useState("");
  const [completed, setCompleted] = useState<{
    key: string;
    result: BudgetResult;
  }>();
  const [error, setError] = useState("");
  const [verifiedKey, setVerifiedKey] = useState("");
  const [checking, setChecking] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [previousKey, setPreviousKey] = useState("");
  const [refresh, setRefresh] = useState(0);
  const revisions = useRef(new PreviewRevisions());
  const input: PreviewInput = {
    workspaceId,
    snapshotId,
    question,
    mode,
    selectedFileIds: [...selected].sort(),
    inputBudgetTokens: Number(budget),
    modelId,
    targetFileId:
      mode === "CLASS_EXPLAIN"
        ? targetFile || [...selected].sort()[0] || null
        : null,
    targetMethodId: targetMethod || null,
  };
  const key = inputKey(input);
  // 入力を元に戻した場合も旧結果を再利用しない。Reactの描画中調整で変更直後から無効化する。
  if (previousKey !== key) {
    setPreviousKey(key);
    setCompleted(undefined);
    setVerifiedKey("");
  }
  const latest = useRef(input);
  latest.current = input;
  const result =
    busy || previousKey !== key
      ? undefined
      : currentResult(completed, input, now);
  const activePreview = useRef(result);
  activePreview.current = result;

  useEffect(() => {
    let cancelled = false;
    const fixed = latest.current;
    if (!validInput(fixed) || busy) return;
    const timer = setTimeout(() => {
      const request = revisions.current.begin(fixed);
      setError("");
      void api<BudgetResult>("/analysis/preview", request)
        .then((response) => {
          if (
            !cancelled &&
            revisions.current.accepts(request, response, latest.current)
          ) {
            setCompleted({ key: inputKey(fixed), result: response });
            setNow(Date.now());
          }
        })
        .catch(() => {
          if (!cancelled) {
            setCompleted(undefined);
            setError(
              "概算を取得できません。接続・予算・モデル設定・主選択・targetを確認し、必要なら再走査してください。",
            );
          }
        });
    }, PREVIEW_DELAY_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [key, busy, refresh]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  async function checkAssembly() {
    if (!result?.canExecute || checking) return;
    const startedKey = key;
    setChecking(true);
    setError("");
    try {
      const request = { ...input, revision: result.revision };
      await api<BudgetResult>(
        `/analysis/preview/${result.previewId}/validate`,
        request,
      );
      if (
        validationIsCurrent(
          startedKey,
          result.previewId,
          latest.current,
          activePreview.current,
        )
      )
        setVerifiedKey(startedKey + result.previewId);
    } catch {
      if (
        validationIsCurrent(
          startedKey,
          result.previewId,
          latest.current,
          activePreview.current,
        )
      ) {
        setCompleted(undefined);
        setError(
          "予算またはpreviewの有効性を確認できませんでした。入力を見直して概算を更新してください。",
        );
      }
    } finally {
      setChecking(false);
    }
  }

  return (
    <section>
      <h2>概算と送信コードの組み立て</h2>
      <p>
        入力予算はtokensの上限です。概算ちょうどでは不足し、安全余裕が必要です。費用の上限を保証しません。APIキー不要・外部API呼出なし。
      </p>
      <label>
        入力トークン予算（tokens）
        <input
          type="number"
          min={1024}
          max={100000}
          step={1}
          value={budget}
          onChange={(event) => setBudget(event.target.value)}
        />
      </label>
      <label>
        モデルID（料金設定のキー）
        <input
          type="password"
          autoComplete="off"
          value={modelId}
          onChange={(event) => setModelId(event.target.value)}
        />
      </label>
      {mode === "CLASS_EXPLAIN" && (
        <label>
          説明対象の主選択ファイル
          <select
            value={input.targetFileId ?? ""}
            onChange={(event) => setTargetFile(event.target.value)}
          >
            {[...selected].sort().map((id) => (
              <option key={id} value={id}>
                {id}
              </option>
            ))}
          </select>
        </label>
      )}
      <label>
        必須メソッドID（任意）
        <input
          value={targetMethod}
          onChange={(event) => setTargetMethod(event.target.value)}
        />
      </label>
      {!validInput(input) && (
        <p>
          質問（4,000文字以内）、主選択1〜100件、モデルID、整数予算1,024〜100,000を指定してください。
        </p>
      )}
      {validInput(input) && !result && !error && (
        <p role="status">概算計算中または旧結果が無効です。</p>
      )}
      {error && <p role="alert">{error}</p>}
      <button
        disabled={!validInput(input) || busy}
        onClick={() => {
          setCompleted(undefined);
          setVerifiedKey("");
          setError("");
          setRefresh((value) => value + 1);
        }}
      >
        概算を更新
      </button>
      {result && <BudgetSummary result={result} />}
      <button
        disabled={!result?.canExecute || checking || busy}
        onClick={() => void checkAssembly()}
      >
        {checking ? "再検証中…" : "組み立て結果をバックエンドで再検証"}
      </button>
      {result && verifiedKey === key + result.previewId && (
        <p role="status">
          予算と有効性を確認しました。外部APIへの送信は行っていません。
        </p>
      )}
    </section>
  );
}

export function BudgetSummary({ result }: { result: BudgetResult }) {
  const price = result.estimatedCost;
  return (
    <div>
      <p>
        必須分（質問・指示等含む） {result.mandatoryPromptTokens} tokens ·
        関連追加分 {result.relatedAdditionalTokens} · 履歴{" "}
        {result.historyTokens}
      </p>
      <p>
        全入力推定 {result.counts.tokens} tokens ＋ 安全余裕{" "}
        {result.marginTokens} ＝ 最低予算 {result.minimumBudgetTokens}
      </p>
      <p>
        主選択の最低予算 {result.mandatoryMinimumBudgetTokens} · 想定最大出力{" "}
        {result.maximumOutputTokens} tokens（入力予算とは別）
      </p>
      <p>
        {result.canExecute
          ? "安全余裕を含む入力予算内です。"
          : "予算不足または安全に組み立てられない主選択があります。選択または予算を変更してください。"}
      </p>
      <p>
        {price
          ? `概算費用 ${price.total.toFixed(6)} ${price.currency}（入力 ${price.inputCost.toFixed(6)} ＋ 最大出力 ${price.maximumOutputCost.toFixed(6)}）。単価/百万tokens: 入力 ${price.inputPerMillion}、出力 ${price.outputPerMillion}。確認日 ${price.checkedOn}`
          : "概算費用：料金不明（未設定モデルを無料として扱いません）"}
      </p>
      {result.warnings.map((warning, index) => (
        <p className="warning" key={index}>
          {warning}
        </p>
      ))}
      {result.selectedChunks.map((chunk) => (
        <details key={chunk.fileId}>
          <summary>
            {chunk.relativePath} {chunk.mandatory ? "主選択・必須" : "関連追加"}
          </summary>
          <p>{chunk.reasons.join(" / ")}</p>
          <p>本文保持メソッド: {chunk.methodIds.join(", ") || "なし"}</p>
          <p>
            省略行:{" "}
            {chunk.omittedRanges
              .map((range) => `${range.beginLine}–${range.endLine}`)
              .join(", ") || "なし"}
          </p>
          <pre>{chunk.code}</pre>
        </details>
      ))}
      <ul>
        {result.excludedCandidates.map((candidate) => (
          <li key={candidate.fileId}>
            {candidate.relativePath}: {candidate.reason}
          </li>
        ))}
      </ul>
      <details>
        <summary>組み立てた全材料・係数版・検証情報</summary>
        <p>
          設定版 {result.configVersion} · 期限 {result.expiresAt}
        </p>
        <pre>{result.promptMaterial}</pre>
        <pre>
          {JSON.stringify(
            { counts: result.counts, formula: result.formula },
            null,
            2,
          )}
        </pre>
      </details>
    </div>
  );
}
