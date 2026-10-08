import { expect, it } from "vitest";
import { validateRuntime } from "./runtime";
it("rejects malformed runtime ports, tokens and protocol versions", () => {
  for (const port of [-1, 65536, "8765"])
    expect(() =>
      validateRuntime({ port, token: "a".repeat(64), protocolVersion: "1" }),
    ).toThrow();
  expect(() =>
    validateRuntime({ port: 8765, token: "bad", protocolVersion: "1" }),
  ).toThrow();
  expect(() =>
    validateRuntime({
      port: 8765,
      token: "a".repeat(64),
      protocolVersion: "2",
    }),
  ).toThrow();
});
it("accepts a local launcher connection", () => {
  expect(
    validateRuntime({ port: 8765, token: "a".repeat(64), protocolVersion: "1" })
      .port,
  ).toBe(8765);
});
