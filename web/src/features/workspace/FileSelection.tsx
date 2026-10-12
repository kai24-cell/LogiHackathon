import type { SourceFile } from "../../types/workspace";

const INITIAL_FILE_COUNT = 3;

/** 全結果を保持し、折り畳まれたファイルも同じ選択集合で管理する。 */
export function FileSelection({
  files,
  selected,
  onToggle,
}: {
  files: SourceFile[];
  selected: ReadonlySet<string>;
  onToggle: (fileId: string) => void;
}) {
  const remaining = files.slice(INITIAL_FILE_COUNT);
  const list = (items: SourceFile[]) => (
    <ul className="file-list">
      {items.map((file) => (
        <li key={file.fileId}>
          <label>
            <input
              type="checkbox"
              checked={selected.has(file.fileId)}
              onChange={() => onToggle(file.fileId)}
            />
            <span>{file.relativePath}</span>
            <small>{file.sizeBytes.toLocaleString()} bytes</small>
          </label>
        </li>
      ))}
    </ul>
  );
  return (
    <details className="file-selection" open>
      <summary>
        走査ファイル {files.length}件 · 主選択 {selected.size}件
      </summary>
      {list(files.slice(0, INITIAL_FILE_COUNT))}
      {remaining.length > 0 && (
        <details>
          <summary>
            その他のファイル（{remaining.length}件・選択
            {remaining.filter((file) => selected.has(file.fileId)).length}件）
          </summary>
          {list(remaining)}
        </details>
      )}
    </details>
  );
}
