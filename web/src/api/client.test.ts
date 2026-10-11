import { afterEach, describe, expect, it, vi } from "vitest";
import { consumeToken } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

async function connectedClient() {
  vi.stubGlobal("window", {
    location: {
      hash: "#connect=test-connection-only",
      pathname: "/",
      search: "",
    },
    history: { replaceState: vi.fn() },
  });
  vi.resetModules();
  return import("./client");
}

describe("HTTP failures", () => {
  it("preserves status and safe codes for settings PUT and preview POST", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: "INVALID_CONNECTION" }), {
          status: 401,
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ code: "INVALID_ORIGIN" }), {
          status: 403,
        }),
      );
    vi.stubGlobal("fetch", fetch);
    const { api } = await connectedClient();
    await expect(
      api(
        "/settings",
        { modelId: "fixture-model", apiKey: "test-key-only" },
        "PUT",
      ),
    ).rejects.toMatchObject({ status: 401, code: "INVALID_CONNECTION" });
    await expect(api("/analysis/preview", {})).rejects.toMatchObject({
      status: 403,
      code: "INVALID_ORIGIN",
    });
    expect(fetch.mock.calls[0][1].method).toBe("PUT");
    expect(fetch.mock.calls[1][1].method).toBe("POST");
  });

  it("never retains unknown error codes, response bodies or fetch exception text", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            code: "request-key-sentinel",
            message: "body-secret-sentinel",
          }),
          { status: 400 },
        ),
      )
      .mockResolvedValueOnce(new Response("secret-sentinel", { status: 404 }))
      .mockRejectedValueOnce(new Error("fetch-secret-sentinel"));
    vi.stubGlobal("fetch", fetch);
    const { api } = await connectedClient();
    for (const status of [400, 404, 0]) {
      try {
        await api("/settings");
        throw new Error("Expected failure");
      } catch (failure) {
        expect(failure).toMatchObject({
          status,
          code: status ? "HTTP_ERROR" : "NETWORK_ERROR",
        });
        expect(String(failure)).not.toContain("sentinel");
        expect(JSON.stringify(failure)).not.toContain("sentinel");
      }
    }
  });

  it("makes no request when connection information was lost on reload", async () => {
    vi.stubGlobal("window", {
      location: { hash: "", pathname: "/", search: "" },
      history: { replaceState: vi.fn() },
    });
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    vi.resetModules();
    const { api } = await import("./client");
    await expect(api("/settings", {}, "PUT")).rejects.toMatchObject({
      status: 0,
      code: "CONNECTION_REQUIRED",
    });
    expect(fetch).not.toHaveBeenCalled();
  });
});
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
