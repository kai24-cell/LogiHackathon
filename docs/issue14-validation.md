# Issue #14 検証記録

## Issue本文からの変更と実装方針

当初は#7・#8完了後に英語生成と日本語翻訳の固定デモを追加するIssue。
ユーザー決定により、#7のmainマージ後、固定providerとデモ認証、実API動作確認基盤を先行実装する。
#8の翻訳本体、会話保存、実験機能は追加しない。最新詳細設計13.3、16.3、19.2を参照する。
mainの#7マージコミットaa2b99aからfeature/issue14-demo-validationを作成した。

## 認証・通信・配布

通常ユーザーキーは従来どおりメモリ保持。ユーザー指定によりGit管理外の`.env`をデモ用ローカル設定として使う。
アプリは秘密をファイルへ書き出さず、明示したファイルだけを最大8KiBまで読む。
起動・GET settingsでは値を読まず、認証済みPOST settings/demoでのみ読み込む。
設定成功レスポンスはモデルID・設定済み状態・設定版・利用経路のみ。キーは含めない。
stub設定は実認証の登録を拒否し、実HTTPクライアントのBeanを作らない。
通常起動とCIはデモファイルを指定せず、`.env`を読まない。
既存package.ps1はtrackedファイルのgit archiveのみを使用するため、Git除外の`.env`はZIPに入らない。
ユーザーの既存.gitignoreと.vscode/settings.jsonの変更は保持し、今回のコミットに含めない。

## 検証経路

scripts/verify_api.ps1（Python標準ライブラリのみ）は専用サンプルを通常のbridge→scan→analysis/search→preview→validate→jobへ通す。
実VS Codeの操作を代替したローカルHTTP検証で、ブラウザ/拡張UIの受入確認とは区別する。
主選択OrderServiceと依存OrderRepositoryを期待するのは、数量検証から保存への処理内容に基づく。
固定回答は任意のプロジェクトを解析した結果とは表示しない。
固定normal/invalid/timeoutにより成功、schema不正、timeoutを確認する。usageはnull。
既存#7テストが予算不足、主選択保持、入力変更、設定変更、hash変更、重複要求を検証する。
再走査・検索一致なしなどの既存#5/#6回帰テストも維持する。

実APIは--realと--allow-paid-apiの両方が必要。生成開始要求は1回のみ。
モデル情報GETとgenerateContent POSTを区別し、失敗しても再試行・モデル切替をしない。
モデル情報の入出力上限・generateContent対応を確認するが、schema対応は実生成時に判明する場合がある。
追加指示により404はMODEL_NOT_FOUND、400はGEMINI_INVALID_REQUESTまたはGEMINI_PRECONDITION_FAILED、401/403はGEMINI_AUTH_FAILEDへ分離した。
429はQuotaFailureがある場合GEMINI_QUOTA_EXCEEDED、それ以外はGEMINI_RATE_LIMITED。割当IDからPerMinute/PerDayが判明する場合だけ時間単位を表示する。
不明な割当ID・statusはUNKNOWN。廃止モデル・APIバージョンの非互換を404だけで断定しない。
errorDetailsはHTTP数値・API定型status・MODEL_INFO/GENERATE・定型limitTypeだけ。生message・metadata・キーを保持せず、画面と検証コマンドも許可リストを通して表示する。
APIの402は支払い条件、500は内部障害、504はtimeoutとして区別。任意の外部reasonから日本語文を作ったり、例外causeへ本文を付けたりしない。
公式確認日2026-10-11：[構造化出力](https://ai.google.dev/gemini-api/docs/structured-output)、
[エラー対応](https://ai.google.dev/gemini-api/docs/troubleshooting)。料金や最新モデルIDを推測で固定しない。
エラーの定型詳細は[Google RPCの公式定義](https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto)を参照。
[モデル廃止情報](https://ai.google.dev/gemini-api/docs/deprecations)は確認用リンクとして案内し、アプリに陳腐化する廃止一覧を埋め込まない。
Interactions APIとgenerateContentのエラー形式は異なるため、別APIのsnake_caseコードを推測で断定しない。

## 確認状況

- stub確認済み：正常・schema不正・timeoutのHTTP確認が成功。キー不要、nullable usage、通常予算・参照検証を確認。
- 実API未確認：最初は設定読込でHTTP 400 / INVALID_DEMO_CONFIG、互換性修正後はHTTP 400 / INVALID_DEMO_KEYで停止。いずれもGemini通信は0回。モデルIDの入力形式は通過したが、モデル対応・利用制限は未確認。キー値は読取結果・ログ・報告・PRへ出力していない。
- 未確認：実ブラウザ/実VS Code操作、配布ZIPの展開受入、翻訳（#8）、日本語期待fixtureの実翻訳との比較。

翻訳は英語構造化回答・参照表・source・nullable usageを引き継ぐ。日本語fixtureは期待データのみで今回の翻訳成功を意味しない。

ローカル設定の互換性を改善し、UTF-8 BOMと外側の引用符を許可した。モデルIDとキーの入力形式を別の固定コードで示す。
書式不正時に設定内容を表示せず、既存のメモリ設定を保持する回帰テストを追加した。

## 自動検証

- Java：分離した.cache/issue14-validation/backendでJava 21・Maven Wrapper・既存ローカル依存を使用し、`verify`成功。120テスト、失敗0、Spotless check、jarビルド成功。
- Web：`format:check`、`lint`、`typecheck`、`test -- --run`（32件）、`build`成功。
- 拡張：`format:check`、`lint`、`typecheck`、`test -- --run`（2件）、`build`、`package`成功。既存のLICENSE/repository未指定警告あり。
- Python：構文チェック成功。Ruffは導入されていないため追加導入しない。実API許可フラグが片方だけのコマンドは起動前に拒否される。
- `git diff --check`成功。.envのGit除外と.env.exampleの全値が空欄であることを確認。

## reviewer結果と対応

既存の専用reviewerエージェントに`.codex/agents/reviewer.toml`を読ませ、差分と設計をレビューした。レビュー中のコード編集なし。
通信・認証・通常生成フローの確実な追加不具合なし。P3検証不足1件：検索候補への包含だけでは依存解析の破損を検出できない。
指摘を妥当と判断し、Service→Repositoryの1hop・正の依存寄与とpreview選定への包含を確認するよう検証スクリプトを補強した。
修正後は実APIを再実行せず、stubで確認する。

後続のユーザー指示でモデル・制限のエラー分類を補強した。HTTP/定型status/失敗段階の保持と秘密を含む未知statusの破棄をstub回帰テストで確認する。
ローカルキーを修正したという返信はまだなく、実APIの再実行は保留する。

再レビューではP3の解消と追加エラー分類の安全性を確認し、新たな確実な不具合なし。最終jarのstub3ケースも成功した。
