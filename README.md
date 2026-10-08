# CheapReview

Windowsのローカルブラウザで動くSpring Bootコード理解・レビュー支援アプリです。
今回の実装範囲は **Web表示 → VS Code拡張接続 → フォルダ選択 → ファイル走査** です。
生成・翻訳・会話TXTは後続段階の必須仕様として扱い、この段階では外部AI APIを呼び出しません。

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
APIキーとGCP認証の設定はこの段階では不要です。

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
| backend/.../{bridge,workspace}/dto | 型付きAPI入出力と状態enum |
| backend/.../api、security | 共通エラーとローカル通信の検証 |
| web/src/features/workspace | 接続監視・走査操作のReact hook |
| web/src/main.tsx | 画面表示 |
| extension/src/bridge.ts | VS CodeダイアログとSpringへのポーリング |

Controllerにメモリ状態やExecutorを置きません。ブリッジの状態遷移と、走査の受付・結果公開・受付解除は各Service内で整合性を保ちます。
待機時間・保持上限は設定または名前付き定数で定義しています。

## 走査の制限・設定例

初期対象はJava本番ソースです。テスト・生成Java・application設定は明示オプションで追加します。
`.git`、node_modules、target、build、dist、秘密ファイル・秘密ディレクトリは固定除外です。
汎用JSON・認証JSON・バイナリは走査しません。許可root外、symlink/junction、ネットワーク共有を拒否します。
対象をビルド・実行・編集せず、依存もインストールしません。

初期上限は単一1MiB、走査エントリー10,000件、合計100MiB、60秒です。
UTF-8読込失敗や上限到達は警告を表示します。スナップショットはメモリ内で相対パス・sha256・サイズを保持します。
秘密を含まない設定例は `backend/config/application.example.properties` です。
必要に応じてSpringの外部設定ファイルや環境変数で変更してください。外部API認証の実値をリポジトリへ保存しないでください。

## ZIP作成

変更をレビューしてコミットし、build.ps1完了後に実行します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package.ps1
```

`dist/cheapreview-source.zip` にGit追跡ソースとjar・VSIXをまとめます。
未追跡の利用者コード、node_modules、依存キャッシュ、runtime.jsonは含めません。
Git管理対象に実キー・認証JSONを入れないでください。作成後にZIP内容を確認します。
同梱jarの実行にはJava 21が必要で、Node.jsは再ビルド時だけ必要です。

## Design deviations

- 今回は初期構築・ブリッジ・ファイル走査までです。AST解析、CURRENT_FILE/OPEN_REFERENCE、検索・予算、4モード、生成・翻訳、会話TXT、第16章の完成デモと比較実験は後続です。第21章を任意機能へ変更していません。
- ファイル一覧は相対パス順のチェックリストです。階層ツリーは後続で拡張します。
- 空ディレクトリの大量走査も制限できるよう、10,000件上限にはディレクトリを含めます。境界テストで警告を確認します。
- 接続が一時的に切れても登録済みワークスペースはアプリ終了まで保持します。再接続時は旧フォルダ要求を破棄し、古いbridgeIdの結果を拒否します。
- この段階のVSIXはdistから手動インストールします。Web内のVSIXダウンロードと生成・翻訳設定画面は後続です。
- 検証用の一時JDK・依存キャッシュは `.tools/`・`.cache/` に置き、Git/ZIPから除外します。OS全体の設定は変更しません。
- 固定依存のRollup 4.64.0では不要コード除去の処理が長時間CPUを占有しました。CPUプロファイルでeffect解析・include処理を確認し、`build.rollupOptions.treeshake=false` にするとWebビルドが1.65秒で完了しました。ローカル配信の初期構築ではこの最適化を無効にします。minifyは有効のままで、JS出力は約192kBです。

PowerShellスクリプトはWindows PowerShell 5.1で日本語を正しく扱うためUTF-8 BOM付きで保存しています。

検証結果と手動確認の状況は [検証記録](docs/issue02-validation.md) に記載します。
