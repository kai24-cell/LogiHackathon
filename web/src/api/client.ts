import { API_TIMEOUT_MS } from "../config";

/** 診断にはステータスと固定コードだけを保持し、本文や認証情報を持たせない。 */
export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
  }
}

const safeCodes = new Set([
  "INVALID_CONNECTION",
  "INVALID_ORIGIN",
  "INVALID_SETTINGS",
  "INVALID_MODEL_ID",
  "INVALID_API_KEY",
  "INVALID_REQUEST",
  "INVALID_PREVIEW_REQUEST",
  "INVALID_SELECTED_FILE",
  "INVALID_TARGET_FILE",
  "INVALID_TARGET_METHOD",
  "STALE_PREVIEW",
  "STALE_SNAPSHOT",
  "NOT_FOUND",
  "BUDGET_TOO_SMALL",
  "SECRET_IN_QUESTION",
  "SECRET_REQUIRES_REVIEW",
  "INTERNAL_ERROR",
  "BUSY",
  "JOB_LIMIT",
  "REQUEST_ID_CONFLICT",
  "FILE_CHANGED",
  "GEMINI_NOT_CONFIGURED",
  "STALE_SETTINGS",
  "GEMINI_AUTH_FAILED",
  "GEMINI_RATE_LIMITED",
  "GENERATION_TIMEOUT",
  "MODEL_UNSUPPORTED",
  "MODEL_LIMIT_EXCEEDED",
  "GEMINI_BLOCKED",
  "OUTPUT_LIMIT",
  "INVALID_ANSWER",
  "RESPONSE_TOO_LARGE",
  "GEMINI_UNAVAILABLE",
  "CANCELLED",
  "GENERATION_FAILED",
  "INVALID_SCOPE",
  "WORKSPACE_LIMIT",
  "SCAN_UNAVAILABLE",
  "NOT_SCANNED",
]);

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
    throw new ApiFailure(0, "CONNECTION_REQUIRED");
  let response: Response;
  try {
    response = await fetch("/api/v1" + path, {
      method: method ?? (body === undefined ? "GET" : "POST"),
      headers: {
        "X-CheapReview-Token": token,
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(API_TIMEOUT_MS),
    });
  } catch {
    // fetchの例外にはURL等が含まれ得るため、その内容を利用者へ転送しない。
    throw new ApiFailure(0, "NETWORK_ERROR");
  }
  if (!response.ok) {
    let code = "HTTP_ERROR";
    try {
      const error: unknown = await response.json();
      if (
        error &&
        typeof error === "object" &&
        "code" in error &&
        typeof error.code === "string" &&
        safeCodes.has(error.code)
      )
        code = error.code;
    } catch {
      // 古いバックエンドやプロキシのHTML応答も、本文を表示せずHTTP状態で診断する。
    }
    throw new ApiFailure(response.status, code);
  }
  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiFailure(response.status, "INVALID_RESPONSE");
  }
}
