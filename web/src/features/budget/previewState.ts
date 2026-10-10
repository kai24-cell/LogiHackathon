import type { BudgetRequest, BudgetResult } from "../../types/budget";

export type PreviewInput = Omit<BudgetRequest, "revision">;

export function inputKey(input: PreviewInput): string {
  return JSON.stringify([
    input.workspaceId,
    input.snapshotId,
    input.question,
    input.mode,
    [...input.selectedFileIds].sort(),
    input.inputBudgetTokens,
    input.modelId,
    input.targetFileId,
    input.targetMethodId,
  ]);
}

/** 入力とrevisionが両方一致する応答だけ採用し、遅れて届いた旧応答を捨てる。 */
export class PreviewRevisions {
  private revision = 0;
  private key = "";

  begin(input: PreviewInput): BudgetRequest {
    this.key = inputKey(input);
    this.revision += 1;
    return {
      ...input,
      selectedFileIds: [...input.selectedFileIds].sort(),
      revision: this.revision,
    };
  }

  accepts(
    request: BudgetRequest,
    result: BudgetResult,
    current: PreviewInput,
  ): boolean {
    return (
      request.revision === this.revision &&
      result.revision === request.revision &&
      result.snapshotId === current.snapshotId &&
      this.key === inputKey(current) &&
      inputKey(request) === inputKey(current)
    );
  }
}

export function validInput(input: PreviewInput): boolean {
  return (
    !!input.question.trim() &&
    [...input.question].length <= 4000 &&
    input.selectedFileIds.length > 0 &&
    input.selectedFileIds.length <= 100 &&
    Number.isInteger(input.inputBudgetTokens) &&
    input.inputBudgetTokens >= 1024 &&
    input.inputBudgetTokens <= 100000 &&
    !!input.modelId.trim() &&
    (input.mode !== "CLASS_EXPLAIN" ||
      input.selectedFileIds.includes(input.targetFileId ?? ""))
  );
}

/** 同じ入力で再計算した場合も、別previewへの旧再検証応答を成功・失敗とも無視する。 */
export function validationIsCurrent(
  startedKey: string,
  previewId: string,
  input: PreviewInput,
  active: BudgetResult | undefined,
): boolean {
  return (
    !!active &&
    active.previewId === previewId &&
    startedKey === inputKey(input) &&
    active.snapshotId === input.snapshotId
  );
}

/** 有効期限と現在の入力を毎回照合し、入力変更直後から旧結果を有効に扱わない。 */
export function currentResult(
  completed: { key: string; result: BudgetResult } | undefined,
  input: PreviewInput,
  now: number,
): BudgetResult | undefined {
  if (
    !completed ||
    completed.key !== inputKey(input) ||
    completed.result.snapshotId !== input.snapshotId ||
    !Number.isFinite(Date.parse(completed.result.expiresAt)) ||
    now >= Date.parse(completed.result.expiresAt)
  )
    return undefined;
  return completed.result;
}
