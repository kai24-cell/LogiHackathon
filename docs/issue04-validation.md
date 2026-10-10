# Issue #4 Java解析の実装・検証

## PR #12レビュー指摘の修正（2026-10-10）

最新の修正・検証は本節を参照。以下の元の実装記録は初回実装時点の結果を保持している。

| 指摘 | 修正前の結果 | 修正後の結果 |
| --- | --- | --- |
| 1：ラムダの同名引数 | `Consumer<External>`の`receiver -> receiver.ping()`が、フィールドAの`ping()`へのHEURISTIC辺になる | 有効なラムダ引数の型を確定できないため、辺を作らず`UNRESOLVED_RECEIVER`を記録 |
| 1：for変数 | フィールドAとfor変数Bが同名のとき、for終了後もB.pingへのRESOLVED辺になる | for内はB.ping、終了後はA.pingへの辺になる。誤った解決結果を採用せず、Aの一意署名からHEURISTIC辺を作る |
| 2：明示import | `import external.Target`が未取得でも、別ファイルの`p.Target`への型参照・呼出し辺がRESOLVEDになる | importの先へ置き換えられないため内部辺を作らず、型参照は`IMPORT_RESOLUTION_MISMATCH`、呼出しは`UNRESOLVED_RECEIVER`を記録 |
| 3：ローカル型ID | 同一行のa()・b()内のLocalが同じ型ID・run()メソッドIDになる | 包含メソッド署名と同名宣言順序で別IDになる。同一メソッドの別ブロック内も区別し、改行・本文変更では維持 |
| 4：Repository | `abstract class CustomRepository implements JpaRepository<Entity, Long>`のrolesが空 | Repository役割と`implements:JpaRepository`の根拠を保持。interfaceのextends判定も維持 |
| 5：Security | 内部SecurityConfigだけのSecurityFilterChain参照で、外側ContainerにもSecurity役割が付く | 所属するSecurityConfigだけに付く。直接参照する通常の型の判定は維持 |

### 実装・可読性

- `LexicalScopes`へ宣言探索を分離し、内側の宣言を優先。for/foreach、ラムダ、catch、resource、ブロックの有効範囲を確認する。型が不明でもシャドーイングは有効なので、外側の変数へ進まない。
- 名前レシーバの検証はRESOLVED/HEURISTIC共通。終了済みfor変数しかない参照も未解決にする。正当な継承メソッドとJDK呼出しは維持する。
- 明示importの「存在」と内部索引の「候補あり」を区別。未取得のimportを同一packageやwildcard候補へ置き換えない。
- ファイル解析、メンバー抽出、通常/compact constructor、DI抽出、推定呼出し、ソースルート推定を意味のある単位に分割。空行と短い日本語Javadoc・コメントで目的と制約を説明する。
- 走査済みASTしか返さない閉じたキャッシュは維持し、「空の値」を返すことで未走査ファイルのディスク再読込を防ぐ理由を記載。
- 未解決レシーバを識別する診断名を追加したため、既存テストの`other.ping()`の期待診断を`UNRESOLVED_RECEIVER`へ更新。曖昧な署名を未解決にする検証は維持。

### 検証

- Java 21 / Maven Wrapper：オフラインで`spotless:apply`、`spotless:check`、`verify`が成功。最終確認は`clean spotless:check verify`で生成物を再作成し、javac release 21でコンパイル。全41テスト（回帰16件を含む）が失敗・エラー・skipなし、Spring Boot jar生成。
- Web / Node.js 24.13.0：`npm.cmd run format:check`、`lint`、`typecheck`、`test -- --run`、`build`が成功。12テスト。現在のweb/node_modulesにPrettierの実行ファイルがなかったため、最新ソース・設定を`.cache/ci-validation-20261008160539/web`へ反映し、既存依存で検証。追跡ファイルの内容一致を確認。
- 拡張：同じ整形・Lint・型チェック・テスト・ビルドが成功。2テスト。
- ローカル実行制限でJava依存jarとWebのesbuildが読み取りエラーになったため、必要な検証は制限外で再実行。外部依存のダウンロード・外部AI API通信は行っていない。
- 回帰テストは`JavaAnalysisService`経由の再現例とSymbolSolverなしの推定処理を確認し、誤った辺の不在、未解決情報、正しい呼出し先、IDの一意性・安定性、役割の所属を検証する。
- 実ブラウザ・実VS Codeでの操作、大規模プロジェクトの性能評価、未pushの修正に対するGitHub Actionsは未確認。
- ユーザーの`.gitignore`・`.vscode/settings.json`の変更を保持。コミット・pushはしていない。

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
