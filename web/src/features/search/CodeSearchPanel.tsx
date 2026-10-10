import { useState } from "react";
import type {
  CodeSearchResult,
  SearchMode,
  SearchRequest,
} from "../../types/search";

const modeLabels: Record<SearchMode, string> = {
  SERVICE_REVIEW: "サービス処理",
  CLASS_EXPLAIN: "選択した型と関連型",
  PROJECT_STRUCTURE: "プロジェクト構造",
  AUTH_ANALYSIS: "認証・認可",
};

export function createSearchRequest(
  question: string,
  selected: ReadonlySet<string>,
  mode: SearchMode,
): SearchRequest | undefined {
  if (!question.trim()) return undefined;
  return { question, selectedFileIds: [...selected].sort(), mode };
}

/** 質問と主選択を検索開始時に固定し、結果が現在の入力と異なる場合を表示する。 */
export function CodeSearchPanel({
  selected,
  busy,
  onSearch,
}: {
  selected: ReadonlySet<string>;
  busy: boolean;
  onSearch: (request: SearchRequest) => Promise<CodeSearchResult>;
}) {
  const [question, setQuestion] = useState("");
  const [mode, setMode] = useState<SearchMode>("SERVICE_REVIEW");
  const [completed, setCompleted] = useState<{
    request: SearchRequest;
    result: CodeSearchResult;
  }>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const request = createSearchRequest(question, selected, mode);
  const changed =
    completed && JSON.stringify(request) !== JSON.stringify(completed.request);

  async function search() {
    if (!request || pending || busy) return;
    setPending(true);
    setError("");
    try {
      const result = await onSearch(request);
      setCompleted({ request, result });
    } catch {
      setCompleted(undefined);
      setError(
        "検索に失敗しました。接続状態を確認し、再走査または再検索してください。",
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <section>
      <h2>関連コード検索</h2>
      <p>
        日本語の質問を翻訳せず検索します。英語の識別子と共通語がない場合、類似度は0になります。依存関係・役割は意味的一致の保証ではありません。
      </p>
      <label>
        質問
        <textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          maxLength={10000}
        />
      </label>
      <label>
        検索観点
        <select
          value={mode}
          onChange={(event) => setMode(event.target.value as SearchMode)}
        >
          {(Object.keys(modeLabels) as SearchMode[]).map((key) => (
            <option value={key} key={key}>
              {modeLabels[key]}
            </option>
          ))}
        </select>
      </label>
      <p>主選択 {selected.size}件。候補の順位でチェック状態を変更しません。</p>
      <button
        disabled={!request || busy || pending}
        onClick={() => void search()}
      >
        {pending ? "検索中…" : "関連コードを検索"}
      </button>
      {error && <p role="alert">{error}</p>}
      {changed && (
        <p className="warning">
          入力または主選択が変わりました。以下は前回検索時の結果です。再検索してください。
        </p>
      )}
      {completed && <SearchResult result={completed.result} />}
    </section>
  );
}

export function SearchResult({ result }: { result: CodeSearchResult }) {
  const { weights } = result;
  return (
    <div>
      <p>
        暫定式：R = {weights.cosine} × 類似度 + {weights.dependency} × 依存 +{" "}
        {weights.roleFit} × 役割（{result.formulaVersion}）
      </p>
      <p>
        検索観点：{modeLabels[result.mode]} · 仮チャンク {result.documentCount}
        件。送信コードの選択・予算判定は行いません。
      </p>
      {result.warnings.map((warning, index) => (
        <p className="warning" key={index}>
          {warning}
        </p>
      ))}
      <table>
        <caption>関連ファイル候補とスコア内訳</caption>
        <thead>
          <tr>
            <th>順位</th>
            <th>ファイル・理由</th>
            <th>類似度／寄与</th>
            <th>依存／寄与</th>
            <th>役割／寄与</th>
            <th>総合</th>
          </tr>
        </thead>
        <tbody>
          {result.candidates.map((candidate) => (
            <tr key={candidate.fileId}>
              <td>{candidate.rank}</td>
              <td>
                {candidate.relativePath}
                {candidate.primarySelected && "（主選択）"}
                <ul>
                  {candidate.reasons.map((reason, index) => (
                    <li key={index}>{reason}</li>
                  ))}
                </ul>
                <small>
                  役割：{candidate.roles.join(" / ") || "根拠なし"} ·
                  代表メソッド行：{candidate.beginLine}
                </small>
              </td>
              <td>
                {candidate.score.cosine.toFixed(4)} /{" "}
                {candidate.score.cosineContribution.toFixed(4)}
              </td>
              <td>
                {candidate.score.dependency.toFixed(4)} /{" "}
                {candidate.score.dependencyContribution.toFixed(4)}
              </td>
              <td>
                {candidate.score.roleFit.toFixed(4)} /{" "}
                {candidate.score.roleContribution.toFixed(4)}
              </td>
              <td>{candidate.score.total.toFixed(4)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {result.candidates.length === 0 && (
        <p>走査済みの検索候補がありません。</p>
      )}
      <details>
        <summary>比較用の正確なスコア・式・入力snapshot</summary>
        <pre>{JSON.stringify(result, null, 2)}</pre>
      </details>
    </div>
  );
}
