import { expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { FileSelection } from "./FileSelection";

it("keeps selected files beyond the first three available under a closed toggle", () => {
  const files = Array.from({ length: 5 }, (_, index) => ({
    fileId: `f${index}`,
    relativePath: `File${index}.java`,
    language: "java",
    sha256: "hash",
    sizeBytes: 100,
  }));
  const html = renderToStaticMarkup(
    <FileSelection
      files={files}
      selected={new Set(["f4"])}
      onToggle={() => {}}
    />,
  );
  expect(html).toContain("その他のファイル（2件・選択1件）");
  expect(html).toContain("File4.java");
  expect(html.match(/checked=""/g)).toHaveLength(1);
  expect(html.match(/type="checkbox"/g)).toHaveLength(5);
  expect(html.match(/open=""/g)).toHaveLength(1);
});
