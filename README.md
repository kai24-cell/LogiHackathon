# CheapReview

Windowsのローカルブラウザで動くSpring Bootコード理解・レビュー支援アプリです。
今回の実装範囲は **Web表示 → VS Code拡張接続 → フォルダ選択 → ファイル走査 → Java解析 → 関連コード検索 → 概算・予算内のコード組み立て → Gemini英語回答** です。
Gemini生成はキー設定後の明示的な送信操作で実APIを呼び出します。翻訳・会話TXTは後続段階の必須仕様として扱います。自動テストはstubを使用し、外部AI APIを呼び出しません。

## 設計書

[詳細設計](docs/cheapreview_detailed_design_version02.md)を[旧要件・基本設計](docs/cheapreview_design_spec_version02.md)より優先します。
詳細設計はファイル名がversion02、本文がversion01（2026年10月6日）です。第21章のTXT保存・ファイル取込・コピペ引継ぎも必須です。

Word 2冊の本文・表・ヘッダー・フッターをMarkdownへ変換しています。
再変換は `python scripts/docx_to_markdown.py`、全テキストの順序照合は `python scripts/verify_docx_markdown.py` です。
Pythonは文書変換だけに必要で、アプリ実行には不要です。

## 前提

- Windows 10/11、ローカルVS Code 1.95以上。WSL・SSH・コンテナの拡張ホストは対象外です。
- JDK 21。`java --version` と `javac --version` が21になるようJAVA_HOME/PATHを設定します。
- Node.js 22.12以上とnpm。Windows PowerShellでは `npm.cmd` を使用します。
- 初回ビルドではnpmとMavenの依存取得のためネットワーク接続が必要です。

Spring Boot・JavaParser・各開発依存の版はpom.xml / package.json / package-lock.jsonで固定しています。
走査・解析・検索・概算にはAPIキー不要です。Gemini生成だけWebでキーを設定します。GCP翻訳認証は今回不要です。

## ビルド・拡張の導入

リポジトリ直下で実行してください。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

実行ポリシーの指定はそのPowerShellプロセスだけに適用します。OS全体の設定は変更しません。
Webのチェック・テスト・ビルド、Springのverify、拡張のチェック・テスト・ビルド・VSIX生成を行います。
成果物は `dist/cheapreview-0.1.0.jar` と `dist/cheapreview-0.1.0.vsix` です。

VS Codeの拡張画面の「…」→「VSIXからのインストール」でVSIXを選択し、必要ならウィンドウを再読み込みします。
Marketplaceへの公開は行いません。

## 起動と走査

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

1. Springが `127.0.0.1:8765` で起動し、ビルド済みWebを同一オリジンで配信します。
2. VS Codeのコマンドパレットで **CheapReview: Open Web** を実行します。ページ再読込で接続情報を失った場合もこのコマンドを使います。
3. Webの「VS Code接続済み」と接続ウィンドウ名を確認します。表示更新には最大10秒程度かかります。
4. Webの「フォルダ選択」を押し、VS Codeのダイアログで `samples/scan-demo` を選びます。選択はキャンセルできます。
5. 初期設定ではOrderService.javaだけが表示され、主選択チェックは全てオフです。「テスト」を有効にして再走査するとOrderServiceTest.javaも追加されます。
6. 起動用PowerShellでCtrl+Cを押すと終了し、子プロセスと一時接続情報を片付けます。

接続情報は起動時だけ `%LOCALAPPDATA%/CheapReview/runtime.json` に作ります。
接続トークンは外部APIキーとは別で、ブラウザではメモリにだけ保持し、URLのfragmentは読み取り後に除去します。
runtime.jsonをGitやZIPに含めないでください。強制終了で残った場合は次回起動で置換されます。
ポート8765の競合やJavaの版違いは起動時に検出します。

## 開発時の起動

一度build.ps1を実行した後、Web側を次で起動します。

```powershell
cd web
npm.cmd run dev
```

別のPowerShellで `scripts/start.ps1 -Dev` を実行すると、Viteの接続URL `http://127.0.0.1:5173` を開きます。
Viteの `/api` はSpringへプロキシされます。開発Originの許可は `-Dev` 指定時のみです。
拡張のOpen WebコマンドはSpring配信側を開きます。

拡張の開発はextension内で `npm.cmd run build` 後、リポジトリをVS Codeで開き、F5の「CheapReview Extension」でExtension Development Hostを起動します。

## 検証コマンド

Javaはbackend内で実行します。Google Java Styleを適用し、verifyにもSpotlessチェックを組み込んでいます。

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check
.\mvnw.cmd verify
```

POSIX環境では `./mvnw` を使います。解析対象のコードを整形しません。

Webはweb内、拡張はextension内で以下を実行します。両方でTypeScript strictが有効です。

```powershell
npm.cmd run format:check
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run test -- --run
npm.cmd run build
```

整形する場合は `npm.cmd run format`、VSIX生成はextension内で `npm.cmd run package` です。
JUnitでは実ファイル走査、サイズ境界、不正UTF-8、hash、Host/Origin/token、要求の原子的claim、期限切れ、走査の同時実行制限を検証します。
Vitestでは接続fragmentの消費・除去とruntime入力検証を確認します。外部AI APIはテストから呼びません。

## GitHub Actions CI

[CIワークフロー](.github/workflows/ci.yml)は全てのpull requestとmainへのpushで実行します。
Windows限定アプリのため、ビルド・JUnit（junction検証を含む）・実サーバー連携テストをWindows 2022 runnerで行います。
JavaはTemurin 21、Node.jsはローカル検証に合わせた24.13.0、補助スモークテストはPython 3.12を使用します。

既存の `scripts/build.ps1` を実行し、Web・拡張それぞれの `npm ci`、`format:check`、`lint`、`typecheck`、`test -- --run`、`build` を確認します。
バックエンドはMaven Wrapperの `verify` でJUnit・ビルド・Spotless＋google-java-formatのチェックを行います。
CIでは `format` や `spotless:apply` を実行せず、整形違反は失敗として扱います。
Windowsのcheckoutでも整形結果を一致させるため、`.gitattributes` で標準の改行をLF、`.cmd` をCRLFに固定しています。

ビルド後に `python scripts/smoke_test.py` でjarを127.0.0.1へ一時起動し、Web配信・接続トークン・フォルダ登録・実ファイル走査を検証します。
VS CodeのGUIはHTTPクライアントで代行します。Gemini・Cloud Translationへの実通信は行わず、APIキーやGitHub Secretsの登録は不要です。
起動中アプリと8765番が競合する場合は、検証用ターミナルだけで`$env:CHEAPREVIEW_SMOKE_PORT='18765'`を設定してスモークテストを実行できます。

成功した実行のActions画面から成果物 `cheapreview-vsix` を取得できます。VSIXのみを7日間保存し、ソース・キャッシュ・runtime.jsonを成果物に含めません。
ワークフローの権限は `contents: read` のみにし、checkoutの認証情報を残さず、同一ブランチの古い実行はキャンセルします。
依存取得用のネットワーク接続は必要です。CIは公開・デプロイ・コミット・pushを行いません。

## 構成と責務

| 場所 | 責務 |
| --- | --- |
| backend/.../Application.java | Spring Boot起動 |
| backend/.../bridge/controller | 接続・フォルダ要求APIのHTTP受付 |
| backend/.../bridge/service | 接続期限、要求キュー、原子的claimと完了処理 |
| backend/.../workspace/controller | 走査・ジョブ・一覧APIのHTTP受付 |
| backend/.../workspace/service | 許可root、非同期走査、スナップショット、パス検証・ファイル読取 |
| backend/.../analysis/{controller,dto,service} | Java構造・Spring役割・依存辺の抽出と結果取得API |
| backend/.../{bridge,workspace}/dto | 型付きAPI入出力と状態enum |
| backend/.../api、security | 共通エラーとローカル通信の検証 |
| web/src/features/workspace | 接続監視・走査操作のReact hook |
| web/src/features/analysis | 型・メソッド・依存・未解決参照の表示 |
| web/src/main.tsx | 画面表示 |
| extension/src/bridge.ts | VS CodeダイアログとSpringへのポーリング |

Controllerにメモリ状態やExecutorを置きません。ブリッジの状態遷移と、走査の受付・結果公開・受付解除は各Service内で整合性を保ちます。
待機時間・保持上限は設定または名前付き定数で定義しています。

## 走査の制限・設定例

初期対象はJava本番ソースです。テスト・生成Java・application設定は明示オプションで追加します。
`.git`、node_modules、target、build、dist、秘密ファイル・秘密ディレクトリは固定除外です。
汎用JSON・認証JSON・バイナリは走査しません。許可root外、symlink/junction、ネットワーク共有を拒否します。
対象をビルド・実行・編集せず、依存もインストールしません。

初期上限は単一1MiB、対象ファイル10,000件、訪問エントリー100,000件、合計100MiB、60秒です。
`cheapreview.scan.max-source-files=10000` は一覧に保持するJava・設定ファイル数、
`cheapreview.scan.max-visited-entries=100000` はルート自身・フォルダ・対象外ファイル・読込失敗を含む訪問数です。
固定除外フォルダも入口の1件を数えますが、その内部には入りません。
旧 `cheapreview.scan.max-files` は廃止したため、外部設定で使用している場合は上記の2設定へ移行してください。
UTF-8読込失敗や上限到達は日本語の警告を表示し、合計容量・対象ファイル数・訪問数・時間の上限で終了した場合は「一部のみ走査されました」と明示します。
単一ファイルの容量超過はそのファイルを除外して継続します。警告のパスなどの詳細と未知の警告コードも表示します。
スナップショットはメモリ内で相対パス・sha256・サイズと、解析用の走査済みJava原文を保持します。原文と絶対rootはバックエンド専用で、ファイル一覧APIには含めません。
秘密を含まない設定例は `backend/config/application.example.properties` です。
必要に応じてSpringの外部設定ファイルや環境変数で変更してください。外部API認証の実値をリポジトリへ保存しないでください。

## Java解析（Issue #4）

起動後、専用サンプル [samples/java-analysis](samples/java-analysis/README.md) を選択して走査し、「Java解析」を押してください。
各ファイルを展開するとpackage、型、フィールド、constructor・methodの署名と行位置、Spring役割の根拠を確認できます。
依存関係と未解決参照は別々に表示します。意図的な構文エラーの `Broken.java` が失敗しても、他の6ファイルは継続します。

JavaParser/SymbolSolverは3.27.1に固定し、Java 21構文で解析します。ソースルート単位のJavaParserTypeSolverには走査済みASTだけを返すキャッシュを渡し、ReflectionTypeSolverはJDKの型を解決します。
対象プロジェクトをビルド・実行したり、依存や除外ファイルを読み込んだりしません。解析中も走査時の原文を使用し、変更後の内容には再走査が必要です。

| 依存辺 | 根拠 |
| --- | --- |
| TYPE_REFERENCE | 型・フィールド・引数・戻り値などで使用するプロジェクト内の型 |
| FIELD_DI | Autowired / Inject / Resourceを付けたフィールドの型 |
| CONSTRUCTOR_DI | 唯一のconstructor、またはAutowired / Inject付きconstructorの引数型 |
| METHOD_CALL | 実際のメソッド呼出し。importだけでは作成しない |

辺は方向、参照元行、confidence、RESOLVED / HEURISTICを保持します。RESOLVEDはSymbolSolverの解決結果、HEURISTICは構文上の型候補または明示レシーバと同名署名の一意候補です。
曖昧な型・オーバーロード、型不明の引数、未解決のvarargs変換などを断定しません。Spring/JPA等の外部依存、Lombok、動的DI、反射は完全には解決できず、未解決は「依存なし」ではありません。
Spring役割はannotation/typeの構文上の根拠と信頼度を付け、DTOの名前だけによる判定は低信頼として区別します。

結果取得APIは `GET /api/v1/workspaces/{workspaceId}/snapshots/{snapshotId}/java-analysis`（接続トークン必須）です。
現在の走査結果とsnapshotIdが違う場合は409 `STALE_SNAPSHOT` を返します。後続の検索・予算・生成用APIは追加していません。
型・メソッドIDは相対パス・完全修飾型名・署名から作り、オーバーロードを区別します。行番号は1始まり・両端を含み、本文はAST整形せず原文を切り出します。
解析失敗ファイルも一覧・原文を保持し、ファイル全体を選択できます。メソッド抽出・圧縮へのフォールバック処理は後続Issueの対象です。

検証内容・制限は [Issue #4検証記録](docs/issue04-validation.md) に記載します。

## ZIP作成

変更をレビューしてコミットし、build.ps1完了後に実行します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package.ps1
```

`dist/cheapreview-source.zip` にGit追跡ソースとjar・VSIXをまとめます。
未追跡の利用者コード、node_modules、依存キャッシュ、runtime.jsonは含めません。
Git管理対象に実キー・認証JSONを入れないでください。作成後にZIP内容を確認します。
同梱jarの実行にはJava 21が必要で、Node.jsは再ビルド時だけ必要です。

## 関連コード検索

関連コード検索は`samples/code-search/expected-searches.json`の固定条件で比較できます。
フォルダ走査後に主選択をチェックし、質問と検索観点を入力して「関連コードを検索」を押してください。
候補には順位、主選択、共通語、最短依存距離、役割、スコアの各寄与を表示します。
暫定式は`R=0.50×cosine+0.30×dependency+0.20×roleFit`。日本語を英訳しないため、共通語なしではcosineが0になります。
係数は`backend/config/application.example.properties`の`cheapreview.search.*-weight`で指定し、アプリ再起動後に適用します。
詳しい試し方、数式、暫定判断、検証は[Issue #5検証記録](docs/issue05-validation.md)を参照してください。

## 概算と予算内の送信コード組み立て

1. 起動・接続後に`samples/code-search`を選び、OrderService.javaなどを主選択します。
2. 検索欄へ日本語の質問を入力します。検索ボタンを先に押す必要はありません。
3. 「概算と送信コードの組み立て」で入力予算を変更します（既定8,192、1,024〜100,000 tokens）。質問・選択・観点・予算・モデルを変更すると300ms後に再計算します。
4. 必須分・関連追加分、最低予算、選定／除外理由、送信メソッド・省略行・全材料を確認します。主選択の必須分が不足すれば選択を減らすか予算を増やします。
5. 予算内の場合「組み立て結果をバックエンドで再検証」で、期限・入力・snapshotと予算を再確認できます。APIキーは不要で、外部APIへ送信しません。previewの期限は5分です。期限切れや失敗時は「概算を更新」を押します。

推定式は `ceil(0.35×ASCII数+1.50×日本語系数+1.00×その他数)`。Unicode code pointを排他的に数え、改行・空白を含めます。固定指示・schema・参照ラベル・コード・原質問を一度だけ連結して計数します。
安全余裕は`max(ceil(推定×0.20),512)`、最低予算は推定＋余裕です。推定4,000 tokensなら最低4,800で、4,000や4,799は不可、4,800は許可です。候補追加のたびに全文と余裕を再計算します。

予算は入力tokensの上限であり、金額の上限ではありません。費用は`(入力推定×入力単価+最大出力2,048×出力単価)/1,000,000`。例えば架空の入力2・出力4 USD/百万tokensなら入力4,000で0.016192 USDです。安全余裕を費用へ再加算せず、出力も入力予算へ重ねません。思考・cache・翻訳の料金は含まない暫定概算です。

係数・安全余裕・最大出力・初期メソッド数は`cheapreview.budget.*`で設定できます。`backend/config/application.example.properties`の料金例は架空で既定では無効です。実モデルを使う際は公式料金を確認し、正確なモデルID・通貨・入力/出力単価・確認日を設定して再起動してください。`prices.<modelId>.context-limit`を設定すると出力枠を予約して入力予算を制限します。未知／料金未設定モデルは「料金不明」であり無料とは扱いません。context上限が未設定の場合も保証しません。

設定ファイル本文は秘密情報対策未確定のため組み立て対象外です。設定を主選択すると不可理由を表示します。その他のJavaコード・質問も、外部送信機能の実装前に秘密情報の扱いを確認する必要があります。
検証ではJava72件・Web20件・拡張2件のテスト、既存の整形チェック・Lint・型チェック・ビルド、VSIX生成が成功しました。配布jarのローカルHTTPでpreview・再検証・古い入力の409・予算不足の422も確認しました。専用reviewer定義を読ませたサブエージェントの指摘3件を修正し、再レビューで解消を確認しました。実ブラウザ・実VS Code操作、大規模プロジェクトでの性能、推定と実API tokensの比較は未確認です。

検証・暫定判断・未確認事項は[Issue #6検証記録](docs/issue06-validation.md)を参照してください。

## Geminiによる英語生成（Issue #7）

1. 最新ソースを`.\scripts\build.ps1`でビルドし直し、`.\scripts\start.ps1`で起動します。すでに起動中ならその起動ターミナルでCtrl+C後に起動し直します。
2. `CheapReview: Open Web`で接続し、`samples/code-search`を走査して主選択と日本語質問を入力します。4モードに対応します。
3. モデルID（`models/`を含まないID）と入力トークン予算を設定し、送信全文・マスク・除外理由・最低予算を確認します。料金未設定は「料金不明」であり無料ではありません。
4. Gemini APIキーをpassword欄に入力し「現在のモデルIDとキーを設定」を押します。キーはSpringメモリだけに保持し、Web入力は空になります。保存・再表示はしません。
5. 「選定コードと質問をGeminiへ送信」を押すと実通信します。モデル情報の上限と保存ソースのhashも検証し、超過・変更は送信前に停止します。
6. 英語回答、送信時の質問、コード、根拠パス・保持行範囲、推定とAPI usageを確認します。翻訳は未実装と明示します。生成中の質問編集は今回のジョブへ混ぜません。
7. キャンセルは課金取消を保証しません。認証・利用制限・timeout・不正JSON等は原因を表示し、自動再試行や追加AI修復を行いません。通信結果が不明な再送は同じrequestIdで重複生成を防ぎます。
8. 使用後に「設定解除」を押します。アプリ終了でもキーを破棄します。キーをREADME、設定例、Git、ログ、TXTへ記載しないでください。

通信timeoutは`cheapreview.generation.timeout-seconds=120`（1〜120秒）です。外部応答は1MiB、ジョブは240秒・最大100件・2時間メモリ保持です。JDK21のHttpClientを使いSDK依存は追加していません。APIキーをURLへ含めず、固定HTTPS宛先へヘッダーで送ります。

設定ファイル本文は引き続き対象外です。Javaの典型的な認証文字列はpreviewで[REDACTED]へ置換し、処理不能な疑わしい情報は停止／任意候補除外します。秘密情報の完全検出は保証しません。実キー通信・実ブラウザ操作は未確認です。検証・後続Issueの接続契約は[Issue #7検証記録](docs/issue07-validation.md)を参照してください。

## Design deviations

- Issue #7は初回質問の英語生成まで。翻訳未実装のため英語回答を明示表示し、日本語直接生成の切替は設けません。履歴・TXT・デモ認証は各後続Issueへ分離します。
- Issue #7の秘密情報処理は名前付き認証リテラル等のマスクと疑わしい情報の送信停止です。任意名・分割・エンコードされた秘密を完全検出する保証はなく、送信前のpreview確認が必要です。

- Issue #6は初回質問のpreview・予算再検証までです。会話保存・生成・翻訳は追加せず、履歴0件と明示します。後続の会話実装では同じ送信材料へ履歴を一度だけ追加し、設計の6往復・25%枠を適用する必要があります。
- 設定ファイル本文は走査時に保存されていないため組み立て対象へ追加読込しません。主選択に設定ファイルがあれば理由を表示して不可とし、黙って除外して成功にはしません。外部送信前の除外・マスキング方針はIssue #2の後続事項として維持します。Javaコードや質問に埋め込まれた秘密を自動除去できる保証もありません。
- 原文は行区間を合併して保持します。同じ行に複数宣言や閉じ括弧がある場合、交差するメソッド全体へ区間を拡張し途中切断を防ぎます。CLASS_EXPLAIN/PROJECT_STRUCTUREの未採用本文は署名を原文から別途表示します。送信材料は再コンパイル用のソースではなく根拠の断片です。

- Issue #5は関連ファイルの順位付けまでです。ファイル内の全メソッドを仮チャンクとしてidfを固定し、ファイルの類似度は最大メソッドcosineで集約します。予算内の送信コード選択・本文圧縮・生成は行いません。
- 依存距離は解析済みの型・メソッド辺をファイルへ投影し、探索時だけ無向化した最短距離（最大2hop）です。CLASS_EXPLAINのtargetは主選択ファイル、関連型は2hop以内の型を持つファイルとします。AUTH_ANALYSISのユーザー関連Serviceはuser/account識別子を暫定根拠にします。質問に合わせたモードの自動推測や翻訳辞書は使いません。
- 検索索引の保持数は初期値1、質問はバックエンドで10,000 code pointsを上限とします。設定ファイルは走査済みファイル名だけを索引化し、本文を追加読込しません。FQNや語彙が一致しない参照・構文解析失敗の限界は検索結果に表示します。
- PR #12の修正では、名前で指定したレシーバの有効な宣言と明示importを、SymbolSolverの解決済み結果にも照合します。未解決の宣言を同名の別変数・型へ置き換えません。ラムダ・for・catchなどのスコープとimport優先順位を回帰テストで確認します。
- FQNを持たないローカル型のIDは、包含する型・メソッド署名と同名ローカル宣言の順序を補助情報にします。同一行でも別宣言を区別し、改行・本文だけの変更ではIDを維持します。同名宣言の追加・並べ替えではIDが変わり得るため、異なるsnapshotの参照は混ぜません。
- 今回は初期構築・ブリッジ・ファイル走査・Java解析・4観点による関連コード検索・予算内の送信材料previewまでです。CURRENT_FILE/OPEN_REFERENCE、生成・翻訳、会話TXT、第16章の完成デモと比較実験は後続です。第21章を任意機能へ変更していません。
- Issue #4の結果確認用にsnapshotを明示する同期GET APIとWebの「Java解析」を追加しました。生成用のpreview/job APIは後続です。JavaParserTypeSolverのキャッシュは走査済み原文から構築し、ライブラリの通常のディスク再読込を遮断します。変更後ファイル・除外ファイル・複数ソースルートをテストします。
- ファイル一覧は相対パス順のチェックリストです。階層ツリーは後続で拡張します。
- 詳細設計8.1の対象ファイル10,000件上限と別に、訪問数100,000件の暫定上限を設けます。通常のフォルダ・対象外ファイルが対象ファイル枠を消費しないようにしつつ、大量の対象外項目や空フォルダの走査を制限します。両方の境界と警告をテストします。
- 接続が一時的に切れても登録済みワークスペースはアプリ終了まで保持します。再接続時は旧フォルダ要求を破棄し、古いbridgeIdの結果を拒否します。
- この段階のVSIXはdistから手動インストールします。Web内のVSIXダウンロードと生成・翻訳設定画面は後続です。
- 検証用の一時JDK・依存キャッシュは `.tools/`・`.cache/` に置き、Git/ZIPから除外します。OS全体の設定は変更しません。
- 固定依存のRollup 4.64.0では不要コード除去の処理が長時間CPUを占有しました。CPUプロファイルでeffect解析・include処理を確認し、`build.rollupOptions.treeshake=false` にするとWebビルドが1.65秒で完了しました。ローカル配信の初期構築ではこの最適化を無効にします。minifyは有効のままで、JS出力は約192kBです。

PowerShellスクリプトはWindows PowerShell 5.1で日本語を正しく扱うためUTF-8 BOM付きで保存しています。

検証結果と手動確認の状況は [検証記録](docs/issue02-validation.md) に記載します。
