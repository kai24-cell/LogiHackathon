import { useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import type {
  BridgeRequest,
  Snapshot,
  ScanOptions,
} from "../../types/workspace";
import {
  REQUEST_POLL_INTERVAL_MS,
  SCAN_WAIT_TIMEOUT_MS,
  FOLDER_WAIT_TIMEOUT_MS,
} from "../../config";

import { useConnection } from "./useConnection";

export function useWorkspace() {
  const { status, connectionMessage } = useConnection();
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [workspace, setWorkspace] = useState<{ id: string; name: string }>();
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [options, setOptions] = useState<ScanOptions>({
    includeTests: false,
    includeGenerated: false,
    includeConfig: false,
  });
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const pause = () =>
    new Promise<void>((resolve) =>
      setTimeout(resolve, REQUEST_POLL_INTERVAL_MS),
    );
  async function scan(id: string) {
    const { scanJobId } = await api<{ scanJobId: string }>(
      `/workspaces/${id}/scan`,
      options,
    );
    const deadline = Date.now() + SCAN_WAIT_TIMEOUT_MS;
    while (alive.current && Date.now() < deadline) {
      const job = await api<{ state: string; errorCode?: string }>(
        `/jobs/${scanJobId}`,
      );
      if (job.state === "FAILED") throw new Error(job.errorCode);
      if (job.state === "SUCCEEDED") {
        const result = await api<Snapshot>(`/workspaces/${id}/files`);
        if (alive.current) {
          setSnapshot(result);
          setSelected(new Set());
          setMessage(`${result.files.length}ファイルを走査しました。`);
        }
        return;
      }
      await pause();
    }
    if (alive.current)
      throw new Error("走査の期限を超過しました。再走査してください。");
  }
  async function pick() {
    setBusy(true);
    try {
      const { requestId } = await api<{ requestId: string }>(
        "/bridge/requests",
        { kind: "FOLDER_PICK" },
      );
      setMessage("VS Codeのフォルダ選択ダイアログを確認してください。");
      const deadline = Date.now() + FOLDER_WAIT_TIMEOUT_MS;
      while (alive.current && Date.now() < deadline) {
        const request = await api<BridgeRequest>(
          `/bridge/requests/${requestId}`,
        );
        if (request.status === "cancelled") {
          setMessage("フォルダ選択を中止しました。");
          return;
        }
        if (request.status === "failed")
          throw new Error("フォルダを選択できませんでした。");
        if (request.status === "completed" && request.result?.workspaceId) {
          setWorkspace({
            id: request.result?.workspaceId,
            name: request.result?.name ?? "選択フォルダ",
          });
          setSnapshot(undefined);
          setSelected(new Set());
          await scan(request.result?.workspaceId);
          return;
        }
        await pause();
      }
      if (alive.current) throw new Error("フォルダ選択の期限を超過しました。");
    } catch (error) {
      if (alive.current) setMessage(String(error));
    } finally {
      if (alive.current) setBusy(false);
    }
  }
  async function rescan() {
    if (!workspace) return;
    setBusy(true);
    try {
      await scan(workspace.id);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setBusy(false);
    }
  }
  return {
    status,
    message: message || connectionMessage,
    busy,
    workspace,
    snapshot,
    selected,
    setSelected,
    options,
    setOptions,
    pick,
    rescan,
  };
}
