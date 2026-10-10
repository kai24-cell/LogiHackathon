import { API_TIMEOUT_MS } from "../config";

export function consumeToken(
  location: Pick<Location, "hash" | "pathname" | "search">,
  history: Pick<History, "replaceState">,
): string {
  const token =
    new URLSearchParams(location.hash.slice(1)).get("connect") ?? "";
  history.replaceState(null, "", location.pathname + location.search);
  return token;
}

const token =
  typeof window === "undefined"
    ? ""
    : consumeToken(window.location, window.history);
export async function api<T>(
  path: string,
  body?: unknown,
  method?: "PUT" | "DELETE",
): Promise<T> {
  if (!token && path !== "/health")
    throw new Error("VS CodeのCheapReview: Open Webから再接続してください。");
  const response = await fetch("/api/v1" + path, {
    method: method ?? (body === undefined ? "GET" : "POST"),
    headers: {
      "X-CheapReview-Token": token,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(API_TIMEOUT_MS),
  });
  if (!response.ok) {
    const error = (await response.json()) as { code?: string };
    throw new Error(error.code ?? "接続できませんでした");
  }
  return response.json() as Promise<T>;
}
