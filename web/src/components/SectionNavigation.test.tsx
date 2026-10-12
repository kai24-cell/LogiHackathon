import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import {
  SectionNavigation,
  SectionPane,
  WelcomeScreen,
} from "./SectionNavigation";

it("shows the centered start screen before the workspace", () => {
  const html = renderToStaticMarkup(<WelcomeScreen onStart={() => {}} />);
  expect(html).toContain("CheapReview");
  expect(html).toContain("LOCAL SPRING BOOT REVIEW");
  expect(html).toContain("スタート");
  expect(html).not.toContain("フォルダ選択");
});

it("maps each section label to an explicit key and selects only the current section", () => {
  const html = renderToStaticMarkup(
    <SectionNavigation section="translation" onChange={() => {}} />,
  );
  expect(html.match(/aria-pressed="true"/g)).toHaveLength(1);
  expect(html).toContain('aria-pressed="true">翻訳');
  for (const label of ["ファイル", "生成", "概算", "翻訳", "引継ぎ"])
    expect(html).toContain(label);
});

it("keeps pane children in the tree while changing only visibility", () => {
  const content = <input aria-label="質問" defaultValue="編集中の質問" />;
  const hidden = renderToStaticMarkup(
    <SectionPane active={false}>{content}</SectionPane>,
  );
  const visible = renderToStaticMarkup(
    <SectionPane active>{content}</SectionPane>,
  );
  expect(hidden).toContain('hidden=""');
  expect(hidden).toContain("編集中の質問");
  expect(visible).not.toContain('hidden=""');
  expect(visible).toContain("編集中の質問");
});
