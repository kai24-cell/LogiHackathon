import { useCallback, useState } from "react";
import { BudgetPanel } from "./features/budget/BudgetPanel";
import { useWorkspace } from "./features/workspace/useWorkspace";
import { AnalysisResult } from "./features/analysis/AnalysisResult";
import { CodeSearchPanel } from "./features/search/CodeSearchPanel";
import { FileSelection } from "./features/workspace/FileSelection";
import { api } from "./api/client";
import type { CodeSearchResult, SearchMode } from "./types/search";
import {
  formatScanWarning,
  scanOptionLabels,
} from "./features/workspace/scanPresentation";
import {
  SectionNavigation,
  SectionPane,
  WelcomeScreen,
} from "./components/SectionNavigation";
import type { WorkspaceSection } from "./components/SectionNavigation";

export function App() {
  const [started, setStarted] = useState(false);
  const [section, setSection] = useState<WorkspaceSection>("files");
  const [searchInput, setSearchInput] = useState<{
    question: string;
    mode: SearchMode;
  }>({ question: "", mode: "SERVICE_REVIEW" });
  const onSearchInput = useCallback(
    (question: string, mode: SearchMode) => setSearchInput({ question, mode }),
    [],
  );
  // スタート前にも正規接続を確認し、案内画面を認証の代わりにはしない。
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
  if (!started) return <WelcomeScreen onStart={() => setStarted(true)} />;

  return (
    <main className="app-shell">
      <div className="connection-strip">
        <span className="status-badge">
          {status?.bridgeConnected ? "VS Code接続済み" : "VS Code未接続"}
        </span>
        <span>{workspace?.name ?? "フォルダ未選択"}</span>
        <small>主選択 {selected.size}件</small>
      </div>
      <SectionNavigation section={section} onChange={setSection} />
      <SectionPane active={section === "files"}>
        <section>
          <h2>フォルダとファイル</h2>
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
          <details>
            <summary>走査オプション</summary>
            <fieldset disabled={busy}>
              <legend>走査対象の追加</legend>
              {(
                Object.keys(
                  scanOptionLabels,
                ) as (keyof typeof scanOptionLabels)[]
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
          </details>
          <p role="status">{message}</p>
          {snapshot && (
            <>
              {snapshot.warnings.map((warning, index) => (
                <p className="warning" key={index}>
                  {formatScanWarning(warning)}
                </p>
              ))}
              <FileSelection
                files={snapshot.files}
                selected={selected}
                onToggle={(fileId) =>
                  setSelected((current) => {
                    const next = new Set(current);
                    if (next.has(fileId)) next.delete(fileId);
                    else next.add(fileId);
                    return next;
                  })
                }
              />
              {snapshot.files.length === 0 && (
                <p>
                  走査可能なJavaソースがありません。対象フォルダと除外設定を確認してください。
                </p>
              )}
            </>
          )}
        </section>
        {analysis && (
          <details>
            <summary>Java解析結果</summary>
            <AnalysisResult result={analysis} />
          </details>
        )}
        <details>
          <summary>拡張の導入と接続</summary>
          <p>
            VSIXをVS
            Codeの「VSIXからのインストール」で導入し、起動後にCheapReview: Open
            Webを実行してください。
          </p>
        </details>
      </SectionPane>
      {workspace && snapshot ? (
        <div key={workspace.id}>
          {/* 同一インスタンスを保ち、移動だけでは再送・再初期化しない。 */}
          <SectionPane active={section === "generate"}>
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
          </SectionPane>
          <SectionPane active={section !== "files"}>
            <BudgetPanel
              workspaceId={workspace.id}
              snapshotId={snapshot.snapshotId}
              question={searchInput.question}
              mode={searchInput.mode}
              selected={selected}
              busy={busy}
              activeSection={section === "files" ? "generate" : section}
            />
          </SectionPane>
        </div>
      ) : (
        section !== "files" && (
          <section className="empty-state">
            <h2>まずフォルダを選択してください</h2>
            <p>
              ファイルセクションでVS
              Codeへ接続し、対象フォルダを走査してください。
            </p>
            <button onClick={() => setSection("files")}>ファイル選択へ</button>
          </section>
        )
      )}
    </main>
  );
}
