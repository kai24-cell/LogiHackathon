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
- 実API確認済み：gemini-3.8-flashで通常生成・予算・schema・参照・usage確認成功（英語表現の目視は未確認）。過去の設定失敗：最初は設定読込でHTTP 400 / INVALID_DEMO_CONFIG、互換性修正後はHTTP 400 / INVALID_DEMO_KEYで停止。いずれもGemini通信は0回。モデルIDの入力形式は通過したが、モデル対応・利用制限は未確認。キー値は読取結果・ログ・報告・PRへ出力していない。
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
この段階では入力確認待ちとして実APIの再実行を保留した。以下の追加確認でアプリの独自検証が原因と判明したため、この保留は解消している。

再レビューではP3の解消と追加エラー分類の安全性を確認し、新たな確実な不具合なし。最終jarのstub3ケースも成功した。

Windows PowerShell 5.1のラッパー直接確認で、日本語を含むUTF-8 BOMなしスクリプトの構文エラーを検出した。
verify_api.ps1をUTF-8 BOM付きに変更し、CIの固定生成3ケースもWindows PowerShellで実行して互換性を検証する。
修正後にPS5.1のラッパー経由でnormal/invalid/timeoutの3ケース成功。reviewerもBOMとCI設定を確認し、追加指摘なし。

## デモキーの独自検証修正と実API1回の確認

### 実ブラウザ受入の保留と再開（2026-10-12）

利用者の指示により#14の接続・実ブラウザ確認を保留し、#8・#9・#10を先行する。Playwright MCPは利用可能で、最新検証済みjarを既存start.ps1 -Demo -NoBrowserで起動し、http://127.0.0.1:8765 のCheapReview画面を開けた。接続fragmentなしの画面はCONNECTION_REQUIREDでフォルダ選択が無効となり、認証保護を確認した。MCP側で起動スクリプトの正規接続情報を安全に移す手段は未確認で、認証を迂回していない。MCPでのデモ設定・概算表示・生成は未実施、実API追加通信0回。キー・接続トークンは出力していない。

再開時は既存の起動ターミナルで必要ならCtrl+C後、最新jarを配置してscripts/start.ps1 -Demoを実行する。VS CodeでCheapReview: Open Webを実行し、その接続済みブラウザをMCPが操作できるよう接続する。トークン付きURLやruntime.jsonをチャットへ貼らない。Webのフォルダ選択後、VS Codeダイアログでsamples/api-validationを選択する操作は手動で行う。OrderService.java主選択・SERVICE_REVIEW・case.jsonの質問・入力予算8192で、まずデモ設定と概算を確認する。実生成の再開には新しい利用者指示を受け、1回だけ送信して英語説明・schema・参照・usageまたは段階・HTTP・timeout・経過msを確認する。離席中は実APIもマージも実行しない。

後続PRのマージ順は#14（PR17）→#8（翻訳）→#9（会話/TXT）→#10（導入/提出）。後続は#14を前提として実装可能だが、各PRで依存関係・実API/実ブラウザ未確認を区別する。

### タイムアウト診断の追加（実API通信なし）

677eaf6での追加実API確認はGENERATION_TIMEOUTで終了し、回答ファイルは未作成だった。当時の検証スクリプトは子プロセスstdout/stderrを破棄し、HttpClientタイムアウト／240秒ジョブ期限の両方を同じ固定コードかつ詳細なしで返した。そのため、既存結果からGET／POST・接続／応答待ち／ジョブ期限を確定することはできない。過去の原因を新しい実装から推測しない。

診断専用クラスは段階enum・ランダム診断ID・UTC時刻・単調時計による経過ms・HTTP数値・timeout enumだけを受け付ける。START／HEADERS／TIMEOUT／ENDをログへ記録し、ヘッダー取得済みなら本文停止時もHTTP値を保持する。TIMEOUTは期限到達時に記録し、キャンセル完了を待たない。キー・URL・本文・モデル・例外メッセージは受け取らない。CONNECTはHttpConnectTimeoutExceptionのみ、RESPONSE_WAITはヘッダー／本文を含む総待機上限、JOB_DEADLINEは240秒のジョブ期限とする。受信していないHTTP値はnullであり0や成功を補わない。接続例外がない総期限から接続原因は断定しない。

ジョブエラー詳細とWeb表示にstage・timeoutType・elapsedMsを追加した。既存HTTPエラーの定型情報は保持する。検証スクリプトは専用ログカテゴリだけを有効化し、許可された固定書式の行のみstdoutへ表示する。任意のログ・生例外・stderrを通さない。今回は実API通信・.env読取／編集・回答本文保存・コミット／pushを実施しない。

ローカルHTTP stubでモデルGETのヘッダー待ち、生成POSTのヘッダー待ち、HTTP 200ヘッダー取得後の本文停止と無再送を検証する。接続例外の分類は合成例外で確認し、外部ネットワークの接続障害を再現しない。ジョブ期限の診断値・固定ログのテストを追加し、実時間240秒のジョブ期限受入は未実施とする。

最終Java verifyは140テスト、失敗0・エラー0、Spotless check・jar生成成功。Web format:check／lint／typecheck／32テスト／build成功。Python構文検証とgit diff --check成功。実際の外部接続タイムアウトと240秒ジョブ期限の通し試験は未実施で、今回の修正から前回の失敗箇所を確定したとは扱わない。

最新jarの固定provider正常・schema不正・timeoutの3ケース成功。検証コマンドが許可されたJOBのSTART／END行だけを表示することを確認した。固定providerは外部HTTPを実行しないため、この3ケースのログにMODEL_INFO／GENERATEがないことは正常。GET／POSTの診断は別途HttpServer stubの回帰テストで確認している。

### 診断追加後の許可済み実確認とレビュー

修正済みjarで許可された検証コマンドを1回実行したが、回答保存用のローカル補助スクリプトの局所import reが診断スレッドのスコープ不具合を起こし、その後LOCAL_VERIFICATION_ERRORで停止した。回答ファイルは未作成で、実APIの段階・timeout種別・経過時間・HTTP値・回答・usageを確定できる記録も取得できなかった。再送は行わず、外部通信の到達回数も推測で補わない。これはGoogleのモデル／利用上限エラーを確認した結果ではない。実API受入は未確認のまま。

ローカル補助の局所importを除去し、stub専用回答ファイルへの保存確認が成功した（実回答ではない）。tracked検証スクリプトにも診断スレッド例外を固定状態として親へ伝える処理を追加し、LOCAL_DIAGNOSTIC_ERRORを検証失敗として扱う。許可された行の抽出・秘密情報を含む例外の非表示のPythonテスト2件成功、CIへ追加した。レビュー／再レビューで確実な追加不具合なし。240秒ジョブ期限から取消・active解放までの通し回帰は低優先の後続課題とし、未確認を維持する。実APIの再送・モデル変更・期限変更・.env編集はしていない。

### 新モデルの通常経路受入確認（2026-10-12）

利用者が.envのモデルをgemini-3.8-flashへ変更した後、新規バックエンドプロセスで設定を読み込んだ。.envの編集・内容出力は行っていない。`verify_api.ps1 -Jar .cache/issue14-validation/backend/target/cheapreview-0.1.0.jar -Real -AllowPaidApi -Port 18768`を1回実行し、通常の走査・解析・検索・preview予算検証・生成・バックエンドschema／参照検証が成功した。再試行・モデル代替は0回。

実usageは入力639、回答出力534、thinking425、合計1,598トークン。キャッシュ値は提供されずnull。英語回答を求める既存system／末尾指示を送信し、構造化回答のsummary存在と参照ID整合を確認した。ただし検証スクリプトは説明文の言語そのものを判定せず、回答全文も保持・表示していないため、英語表現の目視受入は未確認とする。追加通信で確認し直していない。

[公式モデル仕様](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)、[generateContent仕様とthinking既定値](https://ai.google.dev/gemini-api/docs/generate-content/latest-model)、[構造化出力](https://ai.google.dev/gemini-api/docs/generate-content/structured-output)、[公式料金](https://ai.google.dev/gemini-api/docs/pricing)を2026-10-12に確認した。既存v1beta generateContent・responseJsonSchemaに対応し、thinkingは未指定時medium。minimalは非対応のため追加しない。モデル上限は入力1,048,576／出力65,536。アプリ出力枠2,048は維持し、モデル情報GETによる上限照合も通常経路で通過した。generateContent APIと計算式は維持する。送信パラメータの修正については以下のレビュー対応を参照。

Standard有料導入価格は入力USD 0.75／100万、出力USD 3.75／100万（thinkingを含む）、2026-12-31まで。2027-01-01以降はUSD 1.50／7.50。コメント付き料金設定例を追加したが、既定料金や.envは変更していない。今回の実usageに導入価格を適用した参考額は `(639×0.75 + (534+425)×3.75)/1,000,000 = USD 0.0040755`。合計tokenへ出力単価を掛けず、thinkingも二重計上しない。無料枠・契約・請求の確認は未実施で、実請求額ではない。

実ブラウザ／VS Code、翻訳、英語表現の目視、請求の確認は未確認。CIと追加の自動検証はstubのみを使う。

専用reviewerが公式移行条件との不一致を指摘したため、Gemini 3系では非推奨temperatureと非対応candidateCountを省略する修正と送信JSONのstub回帰テストを追加した。旧モデルの設定、schema、出力枠、thinking未指定は維持する。実APIの成功はこの修正前の状態であり、修正後の実API再実行はしていない。generateContent自体や計算式の変更は不要だが、送信パラメータの変更が必要だったことを区別する。

修正後のJava 21／Maven Wrapperオフラインverify（Spotless check、JUnit 136件、jar生成）成功。Webのformat:check／lint／typecheck／test -- --run（32件）／build成功。最初のsandbox実行ではJUnit一時フォルダとesbuild親フォルダアクセスが拒否されたため、承認された再実行で成功を確認した。git diff --check成功。専用reviewerは設定例・料金算術・互換性・関連テストを確認し、送信パラメータと古い確認状況の2指摘に対応後の再レビューで追加指摘なし。.envの読取や実API通信をreviewerに行わせていない。

### 生成404の追加切り分け（2026-10-12）

- GETは `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash`、POSTは同じURLの末尾に `:generateContent` を追加する。ホスト・APIバージョン・モデルIDは一致する。モデルIDの入力検証では `/` や `:` を拒否するため、`models/` の二重付与はなく、このIDではURLエンコードも不要。既存HTTP clientのstubテストでGET/POSTの正確なパスを確認済み。
- 同じローカル設定のキー・v1betaによるモデル一覧GETはHTTP 200。対象の `version` は `001`、`supportedGenerationMethods` は `generateContent`、`countTokens`、`createCachedContent`、`batchGenerateContent`。入力上限1,048,576、出力上限65,536。一覧への掲載・メソッド対応は、この利用者による実生成の可否を保証しない。
- 利用者が許可した追加1回は、アプリ外の最小比較POSTとして専用サンプル・既存system指示・回答schemaを使って実施。HTTP 404 / NOT_FOUND。Googleのmessageは、対象モデルが **新規利用者には利用できない** と説明している。アプリの `MODEL_NOT_FOUND` への変換だけから推測した結論ではない。URL組み立ての不具合ではなく、対象モデルの利用条件による拒否を確認した。
- Google応答は `models/gemini-3.8-flash` とInteractions APIを案内している。ただし、これは今回のエラー応答の案内であり、候補モデルの料金・本アプリのgenerateContent/schema互換性を確認した結果ではない。モデルやAPIを自動変更しない。
- この追加比較で生成POSTは1回、再試行は0回。キー・認証ヘッダー・送信本文・生レスポンス全文を出力／保存していない。モデル一覧取得と比較は明示的なローカル診断で行い、CIには追加しない。
- この比較は通常アプリの予算・参照検証を含む受入試験の代替ではない。実生成成功、実回答のschema・参照検証、usage・費用は未確認。モデル変更後の通常アプリ経路での実API確認には、別途利用者の実行指示が必要。今回はアプリの処理変更なし、既存ユーザー変更も保持した。

追加のHTTP client stub 7テストとSpotless check成功。修正済みjarの固定normal/invalid/timeoutも成功（実APIへの追加通信なし）。

ユーザー指示に基づき`.env`はキー値を出力せず、設定有無・文字数・外側の空白・引用符だけを確認した。空白や引用符の混入はなく、旧検証は拒否、新しいヘッダー安全検証は受理した。入力の修正を求める原因ではなく、アプリの英数字・underscore・hyphen限定/8〜256文字という独自条件が原因だった。接頭辞チェックは元々ない。

通常設定とデモ設定の検証を共通化し、空でないprintable ASCII、4,096文字までを受理する。空白・制御文字・非ASCIIはHTTPヘッダーの安全性、上限は資源保護のために拒否する。外側の対応する単一/二重引用符とBOMは既存ローダーで処理し、シェル展開しない。有効な認証かどうかは実APIで判定する。[公式キー説明](https://ai.google.dev/gemini-api/docs/api-key)で標準キーと認可キーの存在、およびx-goog-api-key利用を確認した（2026-10-11）。

回帰テストでJSONの末尾backslashの部分残存と、長いキーによる正規表現のStackOverflowも検出・修正した。比較用のescape復元と元範囲のマークにより非再帰でマスクし、それ以外のJSON表現を保持する。関連24テスト、Java全体134テスト・Spotless check・verify・jar生成、Web32テスト・整形・Lint・型チェック・buildが成功した。専用reviewerの限定再レビューで新たな確実な漏えい指摘なし。

修正済みjarで`verify_api.ps1 -Real -AllowPaidApi`を一度だけ実行。デモ設定・専用サンプルの走査/解析/検索・予算/preview検証・実モデル情報GETは通過し、generateContent POSTを1回送信した。応答はGENERATE段階のHTTP 404 / NOT_FOUND、固定コードMODEL_NOT_FOUND。モデル情報GETは1回、生成POSTは1回、再送・別モデルへの代替は0回。生成成功・schema/参照の実回答受入・usage/実費は未確認。キー・リクエスト・生応答は記録していない。

GET成功でも実生成は失敗したため、モデル廃止/APIバージョンとの非互換は断定しない。[公式生成API](https://ai.google.dev/api/generate-content)のv1beta/models/{model}:generateContentと宛先構築を照合し、stubでもv1beta prefix/GET/POSTの正確なパスを検証する。次の実通信は利用するモデルの確認とユーザーの再実行指示を待つ。翻訳は未確認のまま。
