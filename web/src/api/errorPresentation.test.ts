import { describe, expect, it } from "vitest";
import { ApiFailure } from "./client";
import { apiErrorMessage } from "./errorPresentation";

describe("safe API diagnostics", () => {
  it("distinguishes connection, origin and settings field failures", () => {
    expect(
      apiErrorMessage(new ApiFailure(401, "INVALID_CONNECTION")),
    ).toContain("HTTP 401 / INVALID_CONNECTION");
    expect(apiErrorMessage(new ApiFailure(403, "INVALID_ORIGIN"))).toContain(
      "scripts/start.ps1 -Dev",
    );
    expect(apiErrorMessage(new ApiFailure(400, "INVALID_MODEL_ID"))).toContain(
      "models/",
    );
    expect(apiErrorMessage(new ApiFailure(400, "INVALID_API_KEY"))).toContain(
      "引用符",
    );
    expect(apiErrorMessage(new ApiFailure(0, "CONNECTION_REQUIRED"))).toContain(
      "ページ再読込",
    );
  });

  it("explains preview inputs and old servers without exposing raw errors", () => {
    expect(
      apiErrorMessage(new ApiFailure(400, "INVALID_PREVIEW_REQUEST")),
    ).toContain("1,024〜100,000");
    expect(apiErrorMessage(new ApiFailure(404, "HTTP_ERROR"))).toContain(
      "再ビルド",
    );
    expect(apiErrorMessage(new ApiFailure(409, "STALE_SNAPSHOT"))).toContain(
      "再走査",
    );
    expect(
      apiErrorMessage(new Error("request-body-key-sentinel")),
    ).not.toContain("sentinel");
  });
});
