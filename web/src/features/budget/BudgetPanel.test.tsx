import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { BudgetPanel } from "./BudgetPanel";

it("shows the model ID as text while keeping the API key masked", () => {
  const html = renderToStaticMarkup(
    <BudgetPanel
      workspaceId="w"
      snapshotId="s"
      question="説明して"
      mode="SERVICE_REVIEW"
      selected={new Set(["f"])}
      busy={false}
    />,
  );
  expect(html).toContain(
    'type="text" autoComplete="off" value="unconfigured-model"',
  );
  expect(html).toContain('type="password" autoComplete="off" value=""');
  expect(html).toContain("入力トークン予算（tokens）");
  expect(html).toContain("キー設定状態を確認できません");
});
