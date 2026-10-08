import { createRoot } from "react-dom/client";
import { useWorkspace } from "./features/workspace/useWorkspace";
import "./style.css";

function App() {
  const {
    status,
    message,
    busy,
    workspace,
    snapshot,
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
        </div>
        <fieldset disabled={busy}>
          <legend>走査対象の追加</legend>
          {(Object.keys(options) as (keyof typeof options)[]).map(
            (key, index) => (
              <label key={key}>
                <input
                  type="checkbox"
                  checked={options[key]}
                  onChange={(event) =>
                    setOptions({ ...options, [key]: event.target.checked })
                  }
                />
                {["テスト", "生成Javaソース", "application設定"][index]}
              </label>
            ),
          )}
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
                {warning}
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
