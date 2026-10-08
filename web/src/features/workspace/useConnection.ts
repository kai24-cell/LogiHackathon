import { useEffect, useState } from "react";
import { api } from "../../api/client";
import {
  APP_VERSION,
  PROTOCOL_VERSION,
  STATUS_POLL_INTERVAL_MS,
} from "../../config";
import type { Status } from "../../types/workspace";

export function useConnection() {
  const [status, setStatus] = useState<Status>();
  const [connectionMessage, setConnectionMessage] =
    useState("接続を確認しています…");

  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const health = await api<{
          appVersion: string;
          protocolVersion: string;
        }>("/health");
        if (
          health.appVersion !== APP_VERSION ||
          health.protocolVersion !== PROTOCOL_VERSION
        ) {
          throw new Error(
            "バージョンが異なります。アプリと拡張を再起動してください。",
          );
        }
        const next = await api<Status>("/status");
        if (!disposed) {
          setStatus(next);
          setConnectionMessage(
            next.bridgeConnected
              ? "フォルダを選択して走査してください。"
              : "VS Code拡張を起動してOpen Webを実行してください。",
          );
        }
      } catch (error) {
        if (!disposed) {
          setStatus(undefined);
          setConnectionMessage(String(error));
        }
      }
      if (!disposed)
        timer = setTimeout(() => void refresh(), STATUS_POLL_INTERVAL_MS);
    }
    void refresh();
    return () => {
      disposed = true;
      clearTimeout(timer);
    };
  }, []);

  return { status, connectionMessage };
}
