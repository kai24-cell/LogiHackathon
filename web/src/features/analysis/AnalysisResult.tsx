import type { JavaAnalysis } from "../../types/analysis";

export function analysisNodeLabels(result: JavaAnalysis): Map<string, string> {
  const labels = new Map<string, string>();
  for (const file of result.files) {
    for (const type of file.types) {
      labels.set(type.typeId, type.qualifiedName);
      for (const method of type.methods)
        labels.set(
          method.methodId,
          `${type.qualifiedName}.${method.signature}`,
        );
    }
  }
  return labels;
}

export function AnalysisResult({ result }: { result: JavaAnalysis }) {
  const labels = analysisNodeLabels(result);
  return (
    <section>
      <h2>Java解析結果</h2>
      <p>
        型・メソッドとプロジェクト内依存を表示します。未解決は「依存なし」を意味しません。
      </p>
      {result.files.map((file) => (
        <details key={file.fileId}>
          <summary>
            {file.relativePath} ·{" "}
            {file.parseStatus === "PARSED" ? "解析済み" : "解析失敗"}
          </summary>
          {file.parseStatus === "FAILED" && (
            <p className="warning">
              構文を解析できませんでした。メソッド抽出はできませんが、ファイル全体の選択は維持できます。他のファイルの解析は継続しました。
            </p>
          )}
          <p>package: {file.packageName || "（既定パッケージ）"}</p>
          {file.types.map((type) => (
            <div key={type.typeId}>
              <h3>
                {type.kind} {type.qualifiedName}（{type.range.beginLine}–
                {type.range.endLine}行）
              </h3>
              <p>
                {type.roles
                  .map((role) => `${role.name} (${role.evidence})`)
                  .join(" / ") || "Spring役割の根拠なし"}
              </p>
              <ul>
                {type.fields.map((field) => (
                  <li key={`${field.name}:${field.range.beginLine}`}>
                    {field.type} {field.name}
                  </li>
                ))}
              </ul>
              <ul>
                {type.methods.map((method) => (
                  <li key={method.methodId}>
                    {method.signature}（{method.range.beginLine}–
                    {method.range.endLine}行）
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </details>
      ))}
      <details>
        <summary>依存関係 {result.edges.length}件</summary>
        <ul>
          {result.edges.map((edge, index) => (
            <li key={index}>
              {labels.get(edge.fromId) ?? edge.fromId} →{" "}
              {labels.get(edge.toId) ?? edge.toId}
              {` · ${edge.kind} · ${edge.resolution === "RESOLVED" ? "解決済み" : "推定（一意候補）"} · ${edge.relativePath}:${edge.line}`}
            </li>
          ))}
        </ul>
      </details>
      <details>
        <summary>未解決の参照 {result.unresolved.length}件</summary>
        <ul>
          {result.unresolved.map((item, index) => (
            <li key={index}>
              {`${item.relativePath}:${item.line} · ${item.kind} · ${item.reference} · ${item.reason}`}
            </li>
          ))}
        </ul>
      </details>
    </section>
  );
}
