import * as vscode from "vscode";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { validateRuntime, type Runtime } from "./runtime";
import {
  APP_VERSION,
  PROTOCOL_VERSION,
  API_TIMEOUT_MS,
  HEARTBEAT_INTERVAL_MS,
  REQUEST_POLL_INTERVAL_MS,
} from "./config";

export class Bridge implements vscode.Disposable {
  private stopped = false;
  private timer?: ReturnType<typeof setTimeout>;
  private runtime?: Runtime;
  private bridgeId?: string;
  private heartbeatAt = 0;
  private dialogBusy = false;
  private readonly windowId = `${vscode.workspace.name ?? "VS Code"} (${process.pid})`;
  start() {
    void this.tick();
  }
  dispose() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.runtime = undefined;
    this.bridgeId = undefined;
  }
  private async readRuntime() {
    const local = process.env.LOCALAPPDATA;
    if (!local) throw new Error("LOCALAPPDATA_MISSING");
    return validateRuntime(
      JSON.parse(
        (
          await readFile(join(local, "CheapReview", "runtime.json"), "utf8")
        ).replace(/^\uFEFF/, ""),
      ),
    );
  }
  private async call<T>(path: string, body?: unknown): Promise<T | undefined> {
    if (!this.runtime) throw new Error("NOT_CONNECTED");
    const response = await fetch(
      `http://127.0.0.1:${this.runtime.port}/api/v1${path}`,
      {
        method: body === undefined ? "GET" : "POST",
        headers: {
          "X-CheapReview-Token": this.runtime.token,
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(API_TIMEOUT_MS),
      },
    );
    if (!response.ok) throw new Error("BRIDGE_REQUEST_FAILED");
    if (
      response.status === 204 ||
      response.headers.get("content-length") === "0"
    )
      return undefined;
    const text = await response.text();
    return text ? (JSON.parse(text) as T) : undefined;
  }
  private async tick() {
    if (this.stopped) return;
    try {
      const runtime = await this.readRuntime();
      if (
        runtime.token !== this.runtime?.token ||
        runtime.port !== this.runtime?.port
      ) {
        this.bridgeId = undefined;
        this.runtime = runtime;
      }
      if (!this.bridgeId) {
        const health = await this.call<{
          appVersion: string;
          protocolVersion: string;
        }>("/health");
        if (
          health?.appVersion !== APP_VERSION ||
          health.protocolVersion !== PROTOCOL_VERSION
        )
          throw new Error("VERSION_MISMATCH");
        const registered = await this.call<{ bridgeId: string }>(
          "/bridge/register",
          { extensionVersion: APP_VERSION, windowId: this.windowId },
        );
        this.bridgeId = registered!.bridgeId;
        this.heartbeatAt = Date.now();
      }
      if (Date.now() - this.heartbeatAt >= HEARTBEAT_INTERVAL_MS) {
        await this.call("/bridge/heartbeat", { bridgeId: this.bridgeId });
        this.heartbeatAt = Date.now();
      }
      if (!this.dialogBusy) {
        const request = await this.call<{ requestId: string; kind: string }>(
          `/bridge/requests/next?bridgeId=${encodeURIComponent(this.bridgeId)}`,
        );
        if (request) {
          this.dialogBusy = true;
          void this.pick(request.requestId, this.bridgeId);
        }
      }
    } catch {
      this.bridgeId = undefined;
    }
    if (!this.stopped)
      this.timer = setTimeout(() => void this.tick(), REQUEST_POLL_INTERVAL_MS);
  }
  private async pick(requestId: string, bridgeId: string) {
    try {
      const selected = await vscode.window.showOpenDialog({
        canSelectFolders: true,
        canSelectFiles: false,
        canSelectMany: false,
        openLabel: "CheapReviewで読み取る",
      });
      if (this.stopped || this.bridgeId !== bridgeId) return;
      const uri = selected?.[0];
      const status = uri
        ? uri.scheme === "file" && !uri.authority
          ? "completed"
          : "failed"
        : "cancelled";
      await this.call(`/bridge/requests/${requestId}/result`, {
        bridgeId,
        status,
        payload: uri && status === "completed" ? { uri: uri.toString() } : {},
      });
    } catch {
      if (!this.stopped)
        void vscode.window.showWarningMessage(
          "フォルダ要求が終了しました。Webから再選択してください。",
        );
    } finally {
      this.dialogBusy = false;
    }
  }
  async openWeb() {
    try {
      this.runtime = await this.readRuntime();
      const health = await this.call<{
        appVersion: string;
        protocolVersion: string;
      }>("/health");
      if (
        health?.appVersion !== APP_VERSION ||
        health.protocolVersion !== PROTOCOL_VERSION
      )
        throw new Error("VERSION_MISMATCH");
      await vscode.env.openExternal(
        vscode.Uri.parse(
          `http://127.0.0.1:${this.runtime.port}/#connect=${this.runtime.token}`,
        ),
      );
    } catch {
      void vscode.window.showErrorMessage(
        "CheapReviewをstart.ps1で起動し、対応する拡張を再起動してください。",
      );
    }
  }
}
