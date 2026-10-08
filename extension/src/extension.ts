import * as vscode from "vscode";
import { Bridge } from "./bridge";
export function activate(context: vscode.ExtensionContext) {
  if (process.platform !== "win32" || vscode.env.remoteName) {
    void vscode.window.showWarningMessage(
      "CheapReviewはWindowsのローカルVS Codeで利用してください。",
    );
    return;
  }
  const bridge = new Bridge();
  context.subscriptions.push(
    bridge,
    vscode.commands.registerCommand("cheapreview.openWeb", () =>
      bridge.openWeb(),
    ),
  );
  bridge.start();
}
