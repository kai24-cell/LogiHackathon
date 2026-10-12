import type { ReactNode } from "react";

const sectionLabels = {
  files: "ファイル",
  generate: "生成",
  budget: "概算",
  translation: "翻訳",
  handoff: "引継ぎ",
} as const;
export type WorkspaceSection = keyof typeof sectionLabels;

export function SectionNavigation({
  section,
  onChange,
}: {
  section: WorkspaceSection;
  onChange: (section: WorkspaceSection) => void;
}) {
  return (
    <nav className="section-navigation" aria-label="表示するセクション">
      {(Object.keys(sectionLabels) as WorkspaceSection[]).map((key) => (
        <button
          type="button"
          key={key}
          aria-pressed={section === key}
          onClick={() => onChange(key)}
        >
          {sectionLabels[key]}
        </button>
      ))}
    </nav>
  );
}

/** 子を保持し、セクション移動で入力・ジョブ・会話を初期化しない。 */
export function SectionPane({
  active,
  children,
}: {
  active: boolean;
  children: ReactNode;
}) {
  return (
    <div className="section-pane" hidden={!active}>
      {children}
    </div>
  );
}

export function WelcomeScreen({ onStart }: { onStart: () => void }) {
  return (
    <main className="welcome-screen">
      <div className="welcome-content">
        <p className="eyebrow">LOCAL SPRING BOOT REVIEW</p>
        <h1>CheapReview</h1>
        <p>フォルダを選び、コードを理解するための準備を始めましょう。</p>
        <button type="button" className="start-button" onClick={onStart}>
          スタート
        </button>
      </div>
    </main>
  );
}
