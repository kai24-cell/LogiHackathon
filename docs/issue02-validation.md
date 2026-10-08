# issue02 初期構築の検証記録

検証日: 2026-10-08（Asia/Tokyo）
ブランチ: `feature/issue02-foundation`

## 実装した範囲

- Word 2冊の本文・表・ヘッダー・フッターをMarkdown化。
- React/Vite、Java 21/Spring Boot/Maven Wrapper、TypeScript VS Code拡張の構成。
- Spotless/google-java-format、Prettier、ESLint、strict型チェック、JUnit、Vitest。
- 秘密ファイル・キャッシュ・成果物のGit除外と秘密を含まない設定例。
- 接続トークン、Host/Origin検証、拡張登録とheartbeat、FOLDER_PICK、非同期走査、相対パス一覧。
- 起動・ビルド・VSIX生成・ZIP作成スクリプトとREADME。

追記されたAGENTS.mdに従い、Controllerから業務処理・接続状態・非同期処理を分離した。
API入出力はDTO、要求・ジョブの状態はenumに変更した。ワイルドカードimportと固定レスポンスのMapは使用していない。
要求のclaimと状態更新、走査受付と完了処理はServiceのロック内で整合性を保つ。
ユーザーが追記したAGENTS.mdは変更していない。

## 実行結果

| 対象 | 実行内容 | 結果 |
| --- | --- | --- |
| Word変換 | docx_to_markdown.py / verify_docx_markdown.py | 成功。旧設計718段落・15表、詳細設計679段落・17表。非空テキスト要素718件・661件が元の順序で一致 |
| Java | Maven Wrapperでspotless:apply、spotless:check、verify | 成功。JUnit 14件、失敗0・エラー0・skip0、jar生成 |
| Web | format:check、lint、typecheck、test -- --run、build | 成功。Vitest 2件。本番ビルドは最適化設定の調整後1.65秒、JS約192kB |
| 拡張 | format:check、lint、typecheck、test -- --run、build、package | 成功。Vitest 2件、dist/cheapreview-0.1.0.vsix生成 |
| 配布jar | WebのdistをSpring staticへコピー後、spotless:checkとpackage | 成功。Javaソースの変更がないため、この再パッケージだけは-DskipTestsで実施 |
| 実サーバー | scripts/smoke_test.py | 成功。jar起動、Web HTML配信、health、未認証401、原子的claim、フォルダ結果登録、実ファイル走査、includeTests切替を確認 |
| PowerShell | 3本のスクリプトの構文解析 | 成功。日本語を含むためUTF-8 BOM付きで保存 |
| 差分 | git diff --check | 成功 |

Java検証はプロジェクト内のTemurin JDK 21.0.12.1、Nodeは24.13.0を使用した。
Mavenのキャッシュと検証JDKは `.cache/` と `.tools/` に置いた。OS全体の設定は変更していない。

JUnitにはWindows junctionをrootとして拒否し、子junctionを追跡しないテストを含む。
走査上限、UTF-8不正、hash更新、Host/Origin/token、APIによる実ファイル走査、要求の同時claim、期限切れ、キャンセル、先着ウィンドウ、走査同時実行制限も確認した。
VS Code部分を実サーバースモークではHTTPクライアントで代行した。実VS CodeのGUI操作を検証したという意味ではない。

## 解決した環境・ビルド問題

- 前回取得したWrapperのスクリプトとonly-script設定が一致していなかった。公式Maven Wrapper 3.3.2のプラグインで再生成し、Maven 3.9.9で成功した。
- 初回の依存取得はサンドボックスの通信制限で失敗したが、許可付き取得は成功した。その後のJava再検証はオフラインで実施した。同一ネットワーク処理が2回失敗して保留した項目はない。
- Webの本番ビルドはネットワーク待ちではなく、Rollup 4.64.0のeffect解析・include処理でCPUを占有していた。CPUプロファイルで確認し、treeshakeを無効にして解消した。詳細はREADMEのDesign deviationsに記載した。
- VSIX生成はrepositoryフィールドとLICENSEがない旨の警告を出したが、生成は成功した。プロジェクトのライセンスを独自に決定していない。

## 未確認・後続範囲

- ブラウザの見た目と実VS Codeでのフォルダダイアログ、選択キャンセル、複数ウィンドウ、切断・再接続の手動操作。
- start.ps1の実ブラウザ起動とCtrl+Cによるruntime.json削除。スクリプト構文とjarの起動は別々に確認した。
- build.ps1全体を新しい環境で一括実行する確認。構成するコマンドは個別に実行した。
- package.ps1による提出ZIPの作成。現在は変更が未コミットのため、ソースが揃ったコミット後に実施する。
- AST解析、検索・予算、4モード、生成・翻訳、会話TXT保存・取込・コピペ、第16章の完成デモ、比較実験。

実キーの参照、Gemini・Cloud Translationへの呼出、有料実験は実施していない。
