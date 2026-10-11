import { ApiFailure } from "./client";

const messages: Record<string, string> = {
  DEMO_NOT_CONFIGURED:
    "デモ用ローカル設定がありません。.envを用意し、デモ設定のパスを指定して起動してください。",
  INVALID_DEMO_CONFIG:
    "デモ用ローカル設定のモデルID・キー・形式を手元で確認してください。内容は共有しないでください。",
  INVALID_DEMO_MODEL:
    ".envのCHEAPREVIEW_DEMO_MODELにモデルIDを設定してください。models/等のパスは付けず、正式なIDを使ってください。",
  INVALID_DEMO_KEY:
    ".envのCHEAPREVIEW_DEMO_GEMINI_KEYの入力形式を手元で確認してください。キーの値は共有しないでください。",
  STUB_ONLY_MODE:
    "固定応答モードでは実APIキーを設定できません。実API用の起動方法へ切り替えてください。",
  STUB_NOT_ENABLED:
    "固定応答providerで起動していません。start.ps1 -Stubを使ってください。",
  CONNECTION_REQUIRED:
    "接続情報がありません。ページ再読込後はVS CodeのCheapReview: Open Webで開き直してください。",
  INVALID_CONNECTION:
    "接続認証が失効しています。バックエンド再起動後はCheapReview: Open Webで開き直してください。",
  INVALID_ORIGIN:
    "HostまたはOriginが許可されていません。127.0.0.1のURLを使い、Vite利用時はバックエンドをscripts/start.ps1 -Devで起動してください。",
  INVALID_MODEL_ID:
    "モデルIDの形式が不正です。models/やURL・空白を含まないID（英数字・ピリオド・ハイフン・アンダースコア、200文字以内）を入力してください。",
  INVALID_API_KEY:
    "APIキーの入力形式が不正です。キーだけを入力し、空白・改行・引用符を含めないでください。",
  INVALID_SETTINGS:
    "モデルIDとAPIキーの形式を確認してください。models/やURL、空白・引用符は含めないでください。",
  INVALID_PREVIEW_REQUEST:
    "概算の入力が不正です。質問・主選択・モデルID・入力トークン予算（1,024〜100,000）を確認してください。",
  INVALID_SELECTED_FILE:
    "主選択が現在のsnapshotと一致しません。再走査して選び直してください。",
  INVALID_TARGET_FILE: "説明対象には主選択のJavaファイルを指定してください。",
  INVALID_TARGET_METHOD:
    "必須メソッドIDが解析結果と一致しません。対象を確認してください。",
  NOT_FOUND:
    "対象がバックエンドにありません。再起動後は再接続し、フォルダを選んで再走査してください。",
  NOT_SCANNED: "走査結果がありません。フォルダを選んで走査してください。",
  INVALID_REQUEST:
    "入力JSONの形式が不正です。最新のWebとバックエンドを組み合わせて使ってください。",
  INVALID_RESPONSE:
    "バックエンドの応答形式を確認できません。最新のビルドで再起動してください。",
  STALE_SNAPSHOT:
    "snapshotが更新されています。再走査して概算を更新してください。",
  STALE_PREVIEW: "概算が古くなりました。概算を更新してください。",
  BUDGET_TOO_SMALL:
    "安全余裕を含む入力トークン予算が不足しています。予算または主選択を変更してください。",
  SECRET_IN_QUESTION:
    "質問に秘密情報の疑いがあります。認証情報を除いて入力してください。",
  SECRET_REQUIRES_REVIEW:
    "主選択コードに秘密情報の疑いがあります。コードを確認して再走査してください。",
  INTERNAL_ERROR:
    "バックエンドの処理に失敗しました。最新のビルドで再起動し、再接続してください。",
  NETWORK_ERROR:
    "バックエンドとの通信を確認できません。起動・接続先を確認してください。自動再送はしません。",
};

/** 本文を表示せず、既知コードの日本語説明とHTTPステータスを示す。 */
export function apiErrorMessage(failure: unknown): string {
  if (!(failure instanceof ApiFailure))
    return "操作に失敗しました。接続と入力を確認してください。";
  const message =
    messages[failure.code] ??
    (failure.status === 401
      ? messages.INVALID_CONNECTION
      : failure.status === 403
        ? messages.INVALID_ORIGIN
        : failure.status === 404 || failure.status === 405
          ? "APIが見つかりません。最新コードを再ビルドしてバックエンドを再起動してください。"
          : "操作に失敗しました。接続と入力を確認してください。");
  return `${message}（${failure.status ? `HTTP ${failure.status} / ` : ""}${failure.code}）`;
}
