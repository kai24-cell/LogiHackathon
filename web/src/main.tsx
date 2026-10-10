import { createRoot } from "react-dom/client";
import { useCallback, useState } from "react";
import { BudgetPanel } from "./features/budget/BudgetPanel";
import type { SearchMode } from "./types/search";
import { useWorkspace } from "./features/workspace/useWorkspace";
import { AnalysisResult } from "./features/analysis/AnalysisResult";
import { CodeSearchPanel } from "./features/search/CodeSearchPanel";
import { api } from "./api/client";
import type { CodeSearchResult } from "./types/search";
import {
  formatScanWarning,
  scanOptionLabels,
} from "./features/workspace/scanPresentation";
import "./style.css";

function App() {
  const [searchInput, setSearchInput] = useState<{
    question: string;
    mode: SearchMode;
  }>({ question: "", mode: "SERVICE_REVIEW" });
  const onSearchInput = useCallback(
    (question: string, mode: SearchMode) => setSearchInput({ question, mode }),
    [],
  );
  const {
    status,
    message,
    busy,
    workspace,
    snapshot,
    analysis,
    analyzeJava,
    selected,
    setSelected,
    options,
    setOptions,
    pick,
    rescan,
  } = useWorkspace();
  return (
    <main>
      <header>
        <p className="eyebrow">LOCAL SPRING BOOT REVIEW</p>
        <h1>CheapReview</h1>
        <p>フォルダを選び、コードを理解するための準備を始めましょう。</p>
      </header>
      <section>
        <h2>接続</h2>
        <p>
          {status?.bridgeConnected
            ? `VS Code接続済み · ${status.windowId}`
            : "VS Code未接続"}
        </p>
        <p>生成・翻訳は次の実装段階です。外部APIは呼び出しません。</p>
      </section>
      <section>
        <h2>ワークスペース</h2>
        <div className="actions">
          <button
            disabled={busy || !status?.bridgeConnected}
            onClick={() => void pick()}
          >
            フォルダ選択
          </button>
          <button disabled={busy || !workspace} onClick={() => void rescan()}>
            再走査
          </button>
          <button
            disabled={busy || !snapshot}
            onClick={() => void analyzeJava()}
          >
            Java解析
          </button>
        </div>
        <fieldset disabled={busy}>
          <legend>走査対象の追加</legend>
          {(
            Object.keys(scanOptionLabels) as (keyof typeof scanOptionLabels)[]
          ).map((key) => (
            <label key={key}>
              <input
                type="checkbox"
                checked={options[key]}
                onChange={(event) =>
                  setOptions({ ...options, [key]: event.target.checked })
                }
              />
              {scanOptionLabels[key]}
            </label>
          ))}
        </fieldset>
        <p role="status">{message}</p>
        <h3>{workspace?.name ?? "フォルダ未選択"}</h3>
        {snapshot && (
          <>
            <p>
              {snapshot.files.length}件 · 主選択 {selected.size}件
            </p>
            {snapshot.warnings.map((warning, index) => (
              <p className="warning" key={index}>
                {formatScanWarning(warning)}
              </p>
            ))}
            <ul>
              {snapshot.files.map((file) => (
                <li key={file.fileId}>
                  <label>
                    <input
                      type="checkbox"
                      checked={selected.has(file.fileId)}
                      onChange={() => {
                        const next = new Set(selected);
                        if (next.has(file.fileId)) next.delete(file.fileId);
                        else next.add(file.fileId);
                        setSelected(next);
                      }}
                    />
                    <span>{file.relativePath}</span>
                    <small>{file.sizeBytes.toLocaleString()} bytes</small>
                  </label>
                </li>
              ))}
            </ul>
            {snapshot.files.length === 0 && (
              <p>
                走査可能なJavaソースがありません。対象フォルダと除外設定を確認してください。
              </p>
            )}
          </>
        )}
      </section>
      {analysis && <AnalysisResult result={analysis} />}
      {workspace && snapshot && (
        <div key={snapshot.snapshotId}>
          <CodeSearchPanel
            key={snapshot.snapshotId}
            selected={selected}
            busy={busy}
            onInputChange={onSearchInput}
            question={searchInput.question}
            mode={searchInput.mode}
            onSearch={(request) =>
              api<CodeSearchResult>(
                `/workspaces/${workspace.id}/snapshots/${snapshot.snapshotId}/code-search`,
                request,
              )
            }
          />
          <BudgetPanel
            workspaceId={workspace.id}
            snapshotId={snapshot.snapshotId}
            question={searchInput.question}
            mode={searchInput.mode}
            selected={selected}
            busy={busy}
          />
        </div>
      )}
      <details>
        <summary>拡張の導入と接続</summary>
        <p>
          READMEの手順でVSIXを作成し、VS
          Codeの「VSIXからのインストール」で導入してください。start.ps1で起動後、CheapReview:
          Open Webで接続できます。
        </p>
      </details>
    </main>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
