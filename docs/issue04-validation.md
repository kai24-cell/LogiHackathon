# Issue #4 Java解析の実装・検証

検証日: 2026-10-09（Asia/Tokyo）
ブランチ: `feature/issue04-java-analysis`
開始点: 最新main `d9602001b14414ac9406f6976506535613b8335a`

## 要件と対象範囲

[Issue #4](https://github.com/kai24-cell/LogiHackathon/issues/4) の本文とコメントをGitHub CLIで取得した。確認時点のコメントは0件。
詳細設計の第7章、8.2・8.3・8.4、9.3を実装の根拠とし、第21章を含む後続の必須仕様も確認した。
今回の対象はJavaParserによる構造・役割・依存解析、構文エラー時の継続、専用サンプルでの結果確認まで。
検索・TF-IDF・依存距離計算・コード選別・予算・生成・翻訳・会話機能は追加していない。

## 実装と判断

- JavaParserとSymbolSolverを3.27.1で固定。既存core依存をsymbol-solver-core依存へ切り替え、coreは同じ版の推移依存を使用する。
- 走査時に安全に読めたJavaだけを`WorkspaceCapture`へ固定。相対パス・hash・ファイル一覧と対応する原文をメモリ保持し、絶対root・全原文は一覧APIへ返さない。後から変更したファイルや除外ファイルを解析の途中へ混ぜない。
- package、import、型（class/interface/enum/record/annotation）、フィールド、record component、明示constructor・compact constructor・method、annotation、参照型、原文本文と行範囲を抽出する。コンパイラが暗黙生成するメソッドは対象外。
- 型・メソッドIDは相対パス・完全修飾型名・署名からSHA-256で生成。オーバーロードを区別し、本文だけの変更ではメソッドIDを維持する。行範囲は1始まり・両端を含む。本文は原文のsubstringで切り出し、CRLFも保持する。
- source rootをpackageと保存先から推定し、rootごとのJavaParserTypeSolverとJDK用ReflectionTypeSolverを使用。標準のJavaParserTypeSolverが走査対象外ソースを再読込しないよう、ファイル・ディレクトリキャッシュは「既知ASTまたは空」を必ず返す。対象プロジェクトのビルド・実行・依存取得は行わない。
- 依存辺は方向付きのTYPE_REFERENCE・FIELD_DI・CONSTRUCTOR_DI・METHOD_CALL。参照元の型またはメソッドID、参照先ID、行、confidenceと解決状態を保持する。関連候補探索・無向化・2hop探索は後続Issue。
- SymbolSolverで一意に解決できるプロジェクト内参照はRESOLVED。未解決の呼出しは明示レシーバ型と一致する同名署名の一意候補だけをHEURISTICとする。型参照の構文候補も解決状態を区別する。曖昧なオーバーロード・不明な引数型・varargs変換などは未解決のまま残す。importだけで呼出し辺を生成しない。解決済みの外部/JDK参照はプロジェクト内の辺に含めない。
- Spring役割はannotation/typeなどの構文上の根拠と信頼度を保持。DTOの名前だけの判定は低信頼。外部ライブラリや実際のDI実行を証明するものではない。
- 構文エラーはFAILEDとPARSE_FAILED警告にして、他ファイルを続行。失敗ファイルも原文と一覧を保持し、既存のファイル全体選択を維持する。本文の選別・圧縮はまだ実装しない。
- 結果確認用APIは `GET /api/v1/workspaces/{workspaceId}/snapshots/{snapshotId}/java-analysis`。接続トークンが必要で、現在のsnapshotと異なる参照は409。Webの「Java解析」で型・メソッド・依存辺・未解決を表示する。

## 検証結果

| 対象 | コマンド・内容 | 結果 |
| --- | --- | --- |
| Java依存 | `mvnw.cmd dependency:resolve` | 成功。取得対象はこのアプリの依存のみ |
| Java | `mvnw.cmd -o spotless:apply verify` | 成功。Spotless check・全25テスト・jar生成。失敗0、エラー0、skip0 |
| 解析追加テスト | JavaAnalysisServiceTest 7件 | 成功。専用サンプル、構文エラー継続、DI・解決済み辺、推定・曖昧参照、importのみの除外、除外/変更ファイル遮断、ID・行位置、record・CRLF、複数ソースルート |
| API | LocalApiTestの走査→Java解析 | 成功。結果取得、古いsnapshotの409、未認証401 |
| Web | `npm run format:check`、`lint`、`typecheck`、`test -- --run`、`build` | 成功。全12テスト。成功/失敗の混在表示、行・推定・未解決表示、IDから名前・署名への表示を含む |
| 拡張 | 同上の既存5コマンド | 成功。全2テスト、ビルド |
| Web同梱jar | `mvnw.cmd -o -DskipTests package` | 成功。全25テスト成功後、Web資材だけ更新した再パッケージ |
| Windows実サーバー | `python scripts/smoke_test.py` | 成功。Web配信、health、トークン、bridge claim、フォルダ登録、走査、テスト追加オプション、Java解析API |
| 差分 | `git diff --check` | 成功 |

Java 21.0.12.1・Node 24.13.0・Python 3.12を使用。
作業フォルダのWeb依存が不完全だったため、前回の検証用コピーの既存依存へ現在のソースを反映してWebのチェックを実行した。
Prettierはそのコピーの実行ファイルで作業フォルダの変更ファイルにも適用した。サンプルコードは整形・ビルド・実行していない。
初回Javaコンパイルのraw型によるエラーを修正した後、最終verifyは成功した。JavaParserのジェネリックAPIに由来するunchecked警告と、既存Mockitoのagent警告は残る。

## 未確認・制限

- GitHub Actions上の新ブランチ実行は未確認。未コミット・未pushのため、CI成功とは記録しない。既存Windows CIは追加JUnitと更新したスモークテストを自動実行する構成。
- 実ブラウザと実VS Codeで、サンプル選択→Java解析→ファイル/依存/未解決の展開、再走査後の表示更新を手動確認する必要がある。Webテストはサーバー側のHTMLレンダリング、実サーバーテストはHTTPクライアントによる代行。
- Lombok、外部Spring/JPA/Security依存、動的DI、反射などは完全解決しない。依存未解決を「依存なし」と扱わない。
- 大規模実プロジェクトでの解析時間・メモリ評価は未実施。今回の同期APIでは生成ジョブ・キャンセル処理を追加していない。
- APIキー・認証ファイルの参照、Gemini/Cloud Translationとの通信は行っていない。application設定の送信前除外・マスキング方針はissue02の記録どおり後続の確認事項。
- ユーザーの `.gitignore` と、作業中に変更された `.vscode/settings.json` は編集・取消していない。

## 主なレビュー対象

- `analysis/service/JavaAnalysisService.java`：解析の処理順とファイル単位の失敗継続。
- `analysis/service/JavaStructureExtractor.java`、`SourceText.java`：ID、constructor/record、位置と原文保持。
- `analysis/service/JavaDependencyExtractor.java`：依存辺の種類、方向、解決済み/推定/未解決の判定。
- `analysis/service/SnapshotTypeSolvers.java`、`workspace/service/WorkspaceCapture.java`：走査範囲外を読み直さない保護と固定スナップショット。
- `JavaAnalysisController.java`、`WorkspaceService.java`：APIとsnapshot照合。
- `web/src/features/analysis/`、`samples/java-analysis/`、`JavaAnalysisServiceTest.java`、`scripts/smoke_test.py`：結果表示と回帰検証。
