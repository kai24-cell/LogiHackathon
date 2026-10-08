import { describe, expect, it, vi } from "vitest";
import { consumeToken } from "./client";
describe("connection token", () => {
  it("consumes the fragment and removes it without preserving it in the URL", () => {
    const replaceState = vi.fn();
    expect(
      consumeToken(
        {
          hash: "#connect=abc%2Bdef",
          pathname: "/",
          search: "?view=workspace",
        },
        { replaceState },
      ),
    ).toBe("abc+def");
    expect(replaceState).toHaveBeenCalledWith(null, "", "/?view=workspace");
  });
  it("does not invent credentials on reload", () => {
    expect(
      consumeToken(
        { hash: "", pathname: "/", search: "" },
        { replaceState: vi.fn() },
      ),
    ).toBe("");
  });
});
