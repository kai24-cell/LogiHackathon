CheapReview

コーディングエージェント向け 詳細設計書

version01  |  2026年10月6日

Windows / ローカルWeb / TypeScript / Spring Boot / Gemini / Cloud Translation

最新の会話で確定した仕様を実装契約としてまとめた文書。旧要件定義version02と矛盾する箇所は、本書を優先する。計算係数は検証前の暫定値であり、論文に基づく最適値ではない。

完成目標：フォルダを選び、予算内で必要コードを抽出し、日本語で質問・追加質問できるアプリをZIPで提出する。電力計測は任意拡張とし、未実装でも提出できる。




# 1. 実装範囲と優先順位

## 1.1 サービスの目的

CheapReviewは、Spring Bootのコードを理解したい開発者が、ファイルをまたぐロジックを少ない入力トークンで質問・レビューできるローカルWebアプリである。ユーザーが選んだファイルを起点に、TF-IDF類似度、依存距離、Springの役割を使って関連コードを抽出する。コードを変更するエージェントではなく、読み取り・説明・レビューに特化する。

質問と検索語は日本語のまま受け取り、Geminiへ英語回答を指示する。英語の構造化結果の説明部分だけをGoogle Cloud Translationで日本語化し、Webに表示する。

## 1.2 優先度と完成条件

| 優先度 | 実装内容 | 完成条件 |
| --- | --- | --- |
| P0 必須 | Web・Spring・VS Code連携、4つの分析モード、追加質問、英語生成・翻訳、履歴TXT保存と引継ぎ | 専用サンプルで日本語結果を表示でき、会話を保存・再利用できる |
| P1 必須 | メソッド抽出、TF-IDF、依存グラフ、予算選択、概算表示 | 選択理由と予算判定を確認でき、超過時は送信しない |
| P2 発表用 | 実トークン、推定誤差、コード保持率、時間、比較実験、CSV | 同一課題で方式別の結果と係数の影響を示せる |
| P3 任意 | 端末側電力計測・Colabモデル比較 | 計測範囲と実測／推定を添えて表示できる |



全4モードを完成対象とする。P0とP1は機能的に結合するため、縦に1本動く実装を作ってから各モードに広げる。電力計測のために必須機能を遅らせない。

## 1.3 対象外

外部Vector DB、Embedding API、PageRank、MMR、コード自動修正、本番の多人数運用、Mac/Linux、WSL/SSH/コンテナ内ワークスペース、クラウド公開は対象外。日本語直接生成の切替と英語比較表示は通常画面に設けない。実験用スクリプトは日本語直接生成を比較条件に持てる。

## 1.4 決定と暫定仕様の区別

ユーザー決定：ローカルブラウザ、ZIP提出、Web中心、Spring処理、Windows、VS Codeフォルダ選択、履歴あり、自由質問、英語生成＋Cloud Translation、専用サンプル、電力最下位。

実装上の暫定決定：React＋Vite、Java 21、Maven Wrapper、JavaParser、REST＋ポーリング、ローカルセッション、タイムアウト、係数、履歴上限。設定として変更可能にする。モデルIDは固定の最新名称を本書で保証せず、デモ環境で動作確認したものを設定する。

# 2. 全体構成と通信

## 2.1 コンポーネント

| 構成 | 責務 | 実装 |
| --- | --- | --- |
| Web | 設定、フォルダ要求、ファイル選択、質問、回答、概算、計測 | TypeScript＋React＋Vite |
| VS Code拡張 | フォルダ選択、現在ファイル取得、バックエンド登録、ブラウザ起動 | TypeScript、VS Code API |
| Spring Boot | ファイル走査、解析、検索、選別、会話、AI・翻訳呼出、計測 | Java 21、Spring Web、Jackson、JavaParser |
| Gemini | 選択したコードから英語の構造化回答を生成 | バックエンドのGeminiClient |
| Cloud Translation | 説明文字列の英日翻訳 | Advanced v3、バックエンド認証 |
| Colab補助実験 | CSV集計、任意の公開モデル電力比較 | Python notebook |



本番用ビルドではWebのdistをSpringのstaticへコピーし、http://127.0.0.1:8765/ から同一オリジンで配信する。Springは127.0.0.1のみで待ち受ける。開発時のViteは /api をSpringへプロキシする。

## 2.2 フォルダ選択の具体的な経路

Web→SpringにFOLDER_PICK要求を登録。拡張が1秒間隔で要求を取得し、showOpenDialogでフォルダを選ぶ。拡張が選択したfile: URIをSpringへ返す。Springは同じWindows上でパスを解決し、ローカルディスクを読み取る。

フォルダの内容をブラウザへアップロードする方式は使わない。拡張はコード解析を担当しない。バックエンドへの読み取り権限は選択済みルートに限定する。

CURRENT_FILE要求では拡張がactiveTextEditor.document.uriを返す。未保存変更がある場合は保存して再解析する案内を表示し、ディスク上のスナップショットを解析する。自動保存やコードの書換えはしない。

## 2.3 ローカル接続

start.ps1がランダムな接続トークンを生成し、%LOCALAPPDATA%/CheapReview/runtime.jsonにportとtokenを書き込む。拡張はそのファイルを読む。起動スクリプトは http://127.0.0.1:8765/#connect=<token> を開く。

Webはhashを読み、接続トークンをメモリへ保持後、URLからhashを除去する。APIではX-CheapReview-Tokenヘッダーを送る。トークンをAPIキーと混同しない。ページ再読込で接続情報を失った場合は拡張のOpen Webコマンドから再接続できる。

runtime.jsonはコードリポジトリ・提出ZIPに含めない。終了時に削除する。Webも拡張も接続前にhealthを確認し、バージョン不一致は再起動を案内する。

# 3. 配置・モジュール・起動

## 3.1 リポジトリ構成

| パス | 内容 |
| --- | --- |
| web/src/features/{settings,workspace,chat,metrics}/ | 主画面の機能別コンポーネント |
| web/src/api/、types/ | APIクライアント、共有DTO型 |
| extension/src/extension.ts、bridge.ts | コマンド登録、接続と要求ポーリング |
| backend/src/main/java/.../api/ | Controller、DTO、例外処理 |
| backend/.../workspace/、analysis/、graph/ | 走査、AST、役割・依存解析 |
| backend/.../vector/、selection/、budget/ | TF-IDF、候補選択、推定 |
| backend/.../conversation/、job/ | 履歴、非同期処理、キャンセル |
| backend/.../provider/、translation/、metrics/ | 外部API、翻訳、測定 |
| backend/src/main/resources/prompts/、schemas/ | 英語プロンプト、回答JSON schema |
| samples/demo-spring/、experiments/、docs/ | 専用サンプル、評価・Colab、導入手順 |
| scripts/start.ps1、build.ps1、package.ps1 | 起動・ビルド・ZIP作成 |



## 3.2 ビルドと提出

Nodeの依存はpackage-lock.json、Javaの依存はpom.xmlでバージョン固定する。実装開始時に互換性のある安定版を選び、Java 21でビルド・動作確認する。VSIXはvsce packageで生成し、成果物をdistへ配置する。

build.ps1：Web npm ci→ビルド→Spring staticへコピー→Maven Wrapperでテストとjar生成→拡張npm ci・ビルド・VSIX生成。package.ps1：jar、vsix、サンプル、ドキュメント、ソース、実験資材を提出用ZIPへまとめる。

提出ZIPには依存キャッシュ、node_modules、実ユーザーAPIキー、認証JSON、実行ログを含めない。JREとVS Codeは利用者側の前提とし、READMEにJava 21・Windows・ネットワーク接続を明記する。

## 3.3 導入ドキュメント

VSIXはVS Code拡張のインストール用ファイル。VS Codeの拡張画面の「VSIXからのインストール」で導入する。Web画面上の生成ボタンはビルド済みVSIXのダウンロードに置き換え、ブラウザ内でビルドしない。生成処理はbuild.ps1が担う。

READMEは、前提ソフト→VSIX導入→デモ認証設定→start.ps1実行→Open Web→サンプル選択→質問→結果確認の順で記載する。初回起動チェックはJava、ポート、拡張接続、翻訳設定を表示する。

# 4. Web画面詳細

## 4.1 1ページの構成

上段に接続状態とデモモード、左側にフォルダ・ファイルツリー、中央に質問・回答、右側または下段に予算と計測情報を置く。下部にVSIXダウンロードと導入説明を折りたたみ表示する。狭い画面では縦並びにする。

| UI | 入力・動作 | 制約 |
| --- | --- | --- |
| モデルID | 自由入力、password型でマスク | 空不可。表示切替なし。秘密情報ではないが希望に合わせる |
| Gemini APIキー | password入力、バックエンドへ設定 | sessionのみ。保存・再表示なし |
| デモモード | backendの事前設定キーを利用 | Webへキーを返さず、設定済み状態だけ返す |
| フォルダ選択 | 拡張へ選択要求 | 接続なしは導入・接続案内 |
| ファイル選択 | チェックボックス | 主選択を必須扱い。概算を更新 |
| 除外設定 | tests・生成ソース・設定類の許可 | 初期は許可なし。固定除外は解除不可 |
| モード | SERVICE_REVIEW / CLASS_EXPLAIN / PROJECT_STRUCTURE / AUTH_ANALYSIS | 全モードで自由質問必須 |
| 入力予算 | 正の整数、tokens | 推定＋安全余裕以上の値が必要 |
| 質問 | textarea | trim後空は送信不可。最大4,000 Unicode文字 |
| 結果 | 日本語説明、コード、参照、計測 | 通常の英語表示・日本語生成切替なし |



## 4.2 概算と送信状態

ファイル・質問・モード・予算を変更すると300ms debounceでpreviewを取得する。計算中は送信不可。応答はrevisionで照合し、古い応答を捨てる。表示項目は必須分、関連追加分、履歴分、全入力推定、安全余裕、最低予算、任意の概算料金。

生成中は送信だけを無効化し、質問の編集は許可する。実行開始時点の入力をジョブにコピーし、以後の編集を実行中ジョブへ反映しない。成功後も編集中の質問を勝手に消さない。

## 4.3 回答と参照

回答はsummary、sections、findings、limitationsの順。findingsは重大度・説明・根拠ファイルと行番号を表示する。コードブロックは原文。参照クリックは拡張へOPEN_REFERENCE要求を送り、許可されたファイルの該当行を開く。

翻訳失敗時だけ英語の結果と「再翻訳」を表示する。再翻訳は保存済み英語JSONを使い、Gemini生成を呼び直さない。キャンセル、予算不足、認証エラー、解析失敗は原因と次の操作を示す。

# 5. REST API契約

## 5.1 共通規則

API prefixは /api/v1。JSON camelCase、UTF-8。日時はUTCのISO 8601、IDはUUID。health以外で接続トークン必須。エラー形式は {code,message,details,requestId}。400入力不正、401接続不正、404なし、409競合・古いsnapshot、422予算不足、429busy、502外部API失敗。

Webからローカル絶対パスを直接登録するAPIは設けない。workspaceIdとrelativePathで参照する。ブリッジ登録時にランダムbridgeIdを返し、拡張専用APIでは接続トークンとbridgeIdを照合する。

| Method・Path | Request | Response・用途 |
| --- | --- | --- |
| GET /health | なし | {status,appVersion,protocolVersion} |
| GET /status | なし | bridgeConnected, demoAvailable, translationReady |
| PUT /settings | modelId, apiKey?, useDemo, inputBudgetTokens | 設定状態のみ。キーを含めない |
| POST /bridge/register | extensionVersion, windowId | bridgeId |
| POST /bridge/heartbeat | bridgeId | 最終接続更新 |
| POST /bridge/requests | kind, payload | requestId。Webから要求登録 |
| GET /bridge/requests/next | bridgeId | pending要求1件を原子的にclaim。なしは204 |
| POST /bridge/requests/{id}/result | bridgeId, status, payload | 要求完了。フォルダ選択ならworkspaceId |
| GET /bridge/requests/{id} | なし | pending/claimed/completed/cancelled/failedと結果 |
| POST /workspaces/{id}/scan | includeTests, includeGenerated, includeConfig | scanJobId |
| GET /workspaces/{id}/files | なし | snapshotId, files, warnings |
| POST /conversations | workspaceId | conversationId |
| DELETE /conversations/{id} | なし | 履歴破棄、204 |
| POST /analysis/preview | 下記AnalysisRequest | PreviewResult |
| POST /analysis/jobs | AnalysisRequest＋previewId＋requestId | jobId、202 |
| GET /jobs/{id} | なし | 状態、進捗、結果、metrics |
| POST /jobs/{id}/cancel | なし | キャンセル要求 |
| POST /jobs/{id}/translate | なし | 翻訳再試行、202 |
| POST /experiments | datasetId, variants, repeats, maxCalls | 実験ジョブ、P2 |
| GET /experiments/{id}/export | format=csv/json | ログのダウンロード |



## 5.2 AnalysisRequest

必須：workspaceId、snapshotId、conversationId、mode、selectedFileIds、question、inputBudgetTokens。CLASS_EXPLAINではtargetFileId必須。任意：targetMethodId、係数のexperimentVariantId。通常画面から任意プロンプト・システム文の差し替えは許可しない。

selectedFileIdsは1件以上。上限100。モードは上記enum。予算は1,024〜100,000の暫定範囲で、モデルの実際のcontext上限に合わせてさらに制限する。未確認モデルは上限保証なしと表示し、外部APIのエラーを処理する。

## 5.3 PreviewResult

previewId、snapshotId、conversationVersion、requestDigest、configVersion、expiresAt、estimatedPromptTokens、marginTokens、minimumBudgetTokens、canExecute、selectedChunks、excludedCandidates、historyTurnsIncluded、historyTurnsOmitted、warnings、estimatedCost?を返す。

previewはメモリで5分保持。生成時にスナップショット・設定・会話版・requestDigestを確認し、違えば409で再preview要求。budgetの比較をクライアントだけに任せず、生成直前にバックエンドでも再検証する。

# 6. ブリッジと処理状態

## 6.1 拡張コマンド

| command ID | 動作 |
| --- | --- |
| cheapreview.openWeb | runtime.jsonを読み、接続URLをブラウザで開く |
| cheapreview.reviewService | Webを開きmode=SERVICE_REVIEWを設定 |
| cheapreview.explainClass | active fileを登録しmode=CLASS_EXPLAIN |
| cheapreview.explainProject | mode=PROJECT_STRUCTURE |
| cheapreview.analyzeAuth | mode=AUTH_ANALYSIS |



モード引渡しは接続後に消費するbootstrap情報としてSpringへ送る。コマンド実行だけでは生成を開始しない。Webで質問・予算・選択を確認して送信する。

## 6.2 ポーリングと期限

拡張は1秒間隔でnext取得、10秒間隔でheartbeat。30秒無応答で切断表示。ダイアログ要求の期限は120秒。キャンセルは正常な選択中止として扱い、エラー表示しない。複数VS Codeウィンドウ時は先に登録した1つを採用し、接続先ウィンドウ名をWebへ表示する。

Webは要求・ジョブを1秒間隔で取得し、終了状態で停止。複数送信はセッション全体で1ジョブまで。requestIdが同じ再送は同じジョブを返し、重複課金を避ける。失敗後の再実行は新requestIdを使う。

## 6.3 Job状態

QUEUED→ANALYZING→SELECTING→GENERATING→TRANSLATING→SUCCEEDED。外部翻訳のみ失敗ならTRANSLATION_FAILEDとして英語JSONを保持。途中失敗はFAILED、キャンセルはCANCELLED。

各ステージ開始・終了時刻と進捗メッセージを保存する。完了後は状態を巻き戻さず、翻訳再試行はtranslationAttemptを増やす。キャンセル時は結果・履歴の追加をしない。外部APIが既に処理した料金は取消できない場合があるためusageは取得できた範囲で残す。

# 7. 内部データモデル

| 型 | 主なフィールド |
| --- | --- |
| SessionSettings | modelId, keySource(USER/DEMO), inputBudgetTokens, configVersion |
| WorkspaceSnapshot | workspaceId, snapshotId, rootPath(backend only), options, createdAt, fileHashes |
| SourceFile | fileId, relativePath, language, sha256, sizeBytes, parseStatus, roles, types, methods |
| MethodInfo | methodId, declaringType, signature, beginLine, endLine, body, annotations, referencedTypes |
| GraphEdge | fromId, toId, kind, confidence, resolution(RESOLVED/HEURISTIC) |
| CodeChunk | chunkId, fileId, methodIds, ranges, renderedSource, mandatory, featureTerms |
| CandidateScore | chunkId, cosine, dependency, roleFit, relevance, estimatedTokens, selected, reasons |
| Conversation | conversationId, workspaceId, version, turns, latestMode |
| ConversationTurn | turnId, questionJa, answerEnglishJson, answerJapaneseJson?, jobId, createdAt |
| AnalysisJob | jobId, requestId, immutableRequest, state, result, metrics, cancellationFlag |
| Measurement | metricName, value?, unit, source, scope, measuredAt, unavailableReason? |



fileIdはsnapshot内のrelativePathから安定したhashで生成。methodIdはrelativePath＋完全修飾型名＋メソッド名＋引数型のhash。オーバーロードを区別する。行番号は1始まりで両端含む。snapshotIdが異なる参照を混ぜない。

セッションと会話はメモリ保持。アプリ終了時に消えるが、ユーザー操作で会話をTXTへダウンロードできる。APIキーはメモリのみ。自動保存する実験ログはメタデータと数値を基本とし、質問全文・コード・回答の保存は専用サンプルの実験のみとする。会話の手動保存・取込は第21章に従う。

# 8. ファイル走査とAST解析

## 8.1 対象範囲

フォルダ選択後は送信対象のファイルチェックを全てオフにする。ユーザーがチェックしたファイルが主選択になる。関連候補の探索は同じ選択済みルート内かつ走査許可されたファイルに限定する。選択フォルダ外へ広げない。

| 区分 | 初期動作 |
| --- | --- |
| Java本番ソース | 解析候補として走査、送信チェックはオフ |
| src/test、*Test.java | 初期除外。includeTestsで有効化 |
| 生成Javaソース | 初期除外。includeGeneratedで有効化 |
| application.yml等の設定 | 初期除外。includeConfigで有効化、認証解析の補助候補 |
| .git、node_modules、target、build、dist | 固定除外 |
| .env、*.pem、認証JSON、binary | 固定除外、秘密情報を自動送信しない |



UTF-8を既定とし、読込失敗は警告して除外。単一ファイル1MiB、走査10,000ファイル・合計100MiBを暫定上限とする。上限到達時は黙って打切らず表示する。symlinkとjunctionはルート外へ追跡しない。正規化後realPathがルート内か確認する。

## 8.2 JavaParser利用

CompilationUnitからpackage、import、型、フィールド、constructor、method、annotation、method call、参照型を抽出。JavaParserTypeSolverをソースルート単位に設定し、ReflectionTypeSolverを加える。対象プロジェクトのビルドや依存ダウンロードは自動実行しない。

解析失敗ファイルは全文選択にフォールバック可能とし、メソッド抽出不可の警告を付ける。解析未解決と「依存なし」は別状態。DTO、Lombok、動的DI、反射の完全解決は保証しない。

## 8.3 Spring役割分類

Controller：Controller/RestController。Service：Service。Repository：RepositoryまたはSpring Data型継承。Entity：Entity。Configuration：Configuration/Bean。Security：SecurityFilterChain、EnableWebSecurity、認証・認可関連型やアノテーション。DTO：recordまたは入出力型として利用される単純データ型。

複数役割を許可する。単なるファイル名一致は低信頼の補助情報とする。主分類の根拠はannotation/typeへ保存して選択理由に使う。

## 8.4 更新とスナップショット

scan時にsha256を計算。preview・生成開始時は主選択と選別済みファイルのhashを再確認し、変更があれば409で再走査を促す。ジョブ開始後は読み込んだコードを不変で保持し、実行途中の編集を混ぜない。

# 9. ベクトル計算と依存グラフ

## 9.1 特徴語と日本語検索

特徴語はクラス・メソッド・型・フィールド名、annotation、コメント、ファイル名から生成。英字は小文字化しcamelCase/snake_caseを分割する。原識別子全体も1語として残す。英数字は単語、日本語連続部分は2文字gramを基本とし、1文字部分はそのまま残す。空白と一般記号は境界にする。

検索語を英訳しない。日本語と英語コードに共通語がない場合、cosine=0となり得る。Springの役割、選択起点、依存関係を補助にするが、意味的Embeddingのような日英対応を主張しない。日本語コメントをサンプルに入れるが、コメントなし課題も評価する。

## 9.2 TF-IDF

文書単位はCodeChunk。ファイル内全メソッドを仮チャンク化して語彙とidfを作り、ユーザーの選択が変わってもsnapshot内ではidfを固定する。

tf(t,d)=0（出現なし）、それ以外は1+ln(count(t,d))。idf(t)=ln((N+1)/(df(t)+1))+1。重み=tf×idf。L2正規化し、cosine(q,d)=Σ(q_t×d_t)。ゼロベクトル同士または片方がゼロなら0。質問にだけ存在する語はスコアへ加えない。

独自性はTF-IDF自体の発明ではなく、Spring役割・依存・予算・コード保持を組み合わせる設計と係数比較に置く。

## 9.3 グラフ

辺はプロジェクト内の型参照、constructor/field DI、method call。method callをSymbolSolverで解決できない場合、レシーバ型と同名signatureの一意候補に限定してHEURISTIC辺を作る。複数候補は未解決として記録し、断定しない。importだけで呼び出しがあると断定しない。

保持グラフは方向付き。関連候補探索だけは無向化して最短距離を求め、元の方向と辺種別は結果に残す。主選択ファイルから最大2hop。自己・直接関連はdependency=1、2hopは0.5、それ以外0。

## 9.4 関連度

R=0.50×cosine+0.30×dependency+0.20×roleFit。各要素は0〜1。係数は非負、合計1とし、設定変更時は検証する。ユーザー主選択はRとは独立にmandatory。

| モード | roleFit=1 | roleFit=0.5 | その他 |
| --- | --- | --- | --- |
| SERVICE_REVIEW | Service | Repository、Entity、Controller、DTO | 0 |
| CLASS_EXPLAIN | target型を含むファイル | 関連型 | 0 |
| PROJECT_STRUCTURE | 主なSpring役割を持つ型 | 分類外のプロジェクト型 | 0 |
| AUTH_ANALYSIS | Security、認証・認可構成 | Controller、ユーザー関連Service | 0 |



スコア同点時はdependency→cosine→relativePath→beginLineの順に固定し、再現性を持たせる。PageRank/MMRは追加しない。

# 10. メソッド抽出と予算選択

## 10.1 コードチャンクの作成

ファイル単位で候補を選び、送信コードは関連メソッドと必要宣言に圧縮する。主選択ファイルは除外しないが、常に全文を送るとは限らない。Webで送信メソッドと省略範囲をpreviewに表示する。

各チャンクにpackage/import、型宣言、annotation、フィールド宣言、constructor、選択メソッドを含める。元の行番号を区間ごとに付ける。AST整形で行番号を変えず、原文の区間を連結して省略箇所を明記する。重複・重なり区間は合併する。

質問とのメソッドcosine上位3つを初期採用し、選択済みメソッドから同じクラス内で呼ぶメソッドを深さ2まで追加する。共通語なしの場合はソース順のpublicメソッド上位3つ。明示targetMethodIdは必須。CLASS_EXPLAINは全メソッド署名を保持し、本文は上記規則。PROJECT_STRUCTUREは役割・型・署名の概要を主にし、本文を必要候補だけにする。AUTH_ANALYSISの主選択Security設定は構成メソッドを全て保持する。

関連ファイルは最大3メソッド＋必要宣言を初期候補にする。長大なメソッドを途中で文字切りしない。mandatoryが予算を超える場合は予算増加か主選択削減を案内する。

## 10.2 文字数による推定

Unicode code pointをASCII（U+0000〜007F、空白・改行も含む）、日本語系（漢字、かな、CJK記号、全角形）、その他の排他的3種類へ分ける。各文字は1回だけ計数する。

T_est=ceil(0.35×N_ascii+1.50×N_ja+1.00×N_other)。日本語系ブロック定義はCharacter分類による関数へ集約し、補助漢字も含める。サロゲートペアを2文字として数えない。

対象はコードだけでなく、質問、system文、履歴、コード参照のラベル、回答schemaを含む送信内容。共通PromptMaterializerが組み立てた文字列で数える。HTTPヘッダー・APIキー・JSONの輸送用エスケープは除く。provider内部のトークン化・schema処理と完全一致しないため実usageで誤差を測る。

## 10.3 安全余裕と実行条件

M(T)=max(ceil(0.20×T),512)。requiredBudget(T)=T+M(T)。実行条件はinputBudgetTokens≧requiredBudget(finalPrompt)。表示例：推定4,000なら余裕800、最低予算4,800。

予算は入力tokensの上限であり、料金の硬い上限ではない。出力上限はmaxOutputTokens=2,048を既定設定とする。対応モデルによって思考tokens等があるため、料金欄で入力・出力・思考・キャッシュを混同しない。

## 10.4 選択手順

1. 新しい質問・system・schemaを固定し、主選択チャンクをmandatoryとして組み立てる。mandatoryだけでrequiredBudgetが上限を超えたら送信不可。

2. 最新から最大6往復の履歴を追加する。履歴の推定は予算の25%まで、かつrequiredBudget条件内。往復単位で採用し、古い順に落とす。最新1往復も入らない場合は追加質問を実行せず、予算増加または新しい会話を案内する。履歴なしの初回は通常処理。

3. 関連候補をdensity=R/max(T_chunk,1)の降順で走査。R=0の任意候補は加えない。各候補を加えた完全promptを再計算し、requiredBudget条件を満たすものだけ採用。大きい候補が入らなくても次候補を見る。

4. 最終promptを再計算し、重複区間・履歴・設定版を検証。selectedReasonsとskippedReasonsを記録する。

Mが入力全体に依存するので、単純にbudget−marginを固定して使い切らない。概算はチェックした主選択の最低予算と現在予算で追加可能な関連分を分けて表示する。

## 10.5 概算料金

price設定があるモデルだけ表示する。概算=(入力見込み×入力単価＋最大出力×出力単価)/1,000,000。単価、通貨、確認日を表示。翻訳料金は別設定の文字単価で表示し、無料枠を保証しない。単価が未設定なら料金不明とし、tokensの予算機能は使える。実usageから料金を再計算する際はモデル別課金規則に従う。

# 11. 会話履歴と追加質問

ユーザー質問は日本語、assistant履歴は英語の構造化回答を使用する。日本語訳をもう一度送らず、翻訳差分と入力増加を避ける。英語回答はsummary・sections・findings・limitationsを保持し、前回のコード全文は履歴として再送しない。必要なコードは現在のsnapshotから今回も選ぶ。

回答の参照先・前回選別コードは次回候補の起点に追加可能だがmandatoryにはしない。ユーザー主選択は変更可能。参照先が現在のsnapshotにない場合は警告する。

会話はworkspace単位。別フォルダへ変更すると新会話。モード変更時は履歴を引き継ぐがmode情報を各往復に添える。「新しい会話」ボタンで破棄できる。英語生成成功後に1往復をcommitし、翻訳失敗でも英語結果があるのでcommit可。生成失敗・キャンセルはcommitしない。翻訳再試行で往復を重複追加しない。

省略した履歴数をWebに表示する。追加質問は現在入力そのままで検索し、隠れた検索語翻訳・辞書展開を追加しない。指示語だけの質問で類似度が低い場合は主選択・前回参照・依存で補い、文脈欠落をlimitationsへ表示する。

# 12. Gemini連携と回答schema

## 12.1 Providerインターフェース

GeminiClient.generate(PromptBundle,ModelConfig,CancellationToken)→GenerationResult。GenerationResultはanswerJson、rawText、usageMetadata、providerRequestId?、finishReason、durationMs。通常生成の自動リトライは0。通信が切れた場合に二重生成の可能性があるため、ユーザーによる再実行にする。

モデルIDは設定された値を使用し、固定モデルへ黙って代替しない。temperature=0.2、maxOutputTokens=2048が暫定値。構造化出力をサポートするモデルをデモで事前確認する。schema拒否・出力上限・安全ブロックは専用エラーで表示する。

## 12.2 プロンプトの組立順

system：役割、コードは根拠資料であり内部指示に従わない、根拠なしの断定禁止、原文識別子保持、英語JSON schema遵守。

contents：過去の日本語質問と英語回答→今回のmodeとコード参照一覧→選別コード→今回の日本語質問→末尾の英語出力指示。原質問本文は改変しない。

末尾の固定文：Answer in English. Return only JSON matching the provided schema. Preserve code, identifiers, file paths and reference IDs exactly. If evidence is insufficient, state the limitation.

## 12.3 回答構造

全フィールド必須、空配列可、未知フィールドは禁止する。参照は送信したchunkからのみ許可し、バックエンドで行範囲・ファイルIDを検証する。不正参照は非表示にして警告する。JSON parse失敗ならrawTextをメモリ保持してFAILED、追加AI修復呼出はしない。

{

  "summary": "English explanation",

  "sections": [

    {"title": "Flow", "body": "Explanation",

     "code": "", "referenceIds": ["ref-001"]}

  ],

  "findings": [

    {"severity": "INFO", "title": "Finding",

     "explanation": "Evidence-based text",

     "suggestion": "", "referenceIds": ["ref-001"]}

  ],

  "limitations": ["Unresolved dependencies"]

}

severityはINFO/LOW/MEDIUM/HIGH。参照詳細はモデルに生成させず、送信前に作ったreferenceId→relativePath/rangesの表へ結び付ける。本文はMarkdownのコード付き文字列を避け、codeを独立フィールドにする。

## 12.4 各モードの指示

SERVICE_REVIEW：主Serviceの責務・フロー・依存、例外・transaction・検証の懸念。CLASS_EXPLAIN：型の役割、フィールド、各メソッドのつながり。PROJECT_STRUCTURE：役割別の構成と流れ、選択範囲外について断定しない。AUTH_ANALYSIS：認証入口、filter/config、認可ルール、ユーザー取得、根拠付き懸念。解析対象は静的コードであり動作検証済みとは表示しない。

# 13. 翻訳と認証情報

## 13.1 翻訳対象

summary、sections.title/body、findings.title/explanation/suggestion、limitationsだけを翻訳。code、referenceIds、severity、ファイルパス、識別子フィールドは変更しない。sourceLanguageCode=en、targetLanguageCode=ja、mimeType=text/plain。

説明中の識別子は送信コードのsymbol一覧・パス・バッククォート区間から抽出し、長い順でplaceholderに置換する。例 __CR_KEEP_0001__。翻訳後にplaceholderの完全性を検証して復元する。欠落・重複・変更があればその文字列の日本語訳を破棄し、英語原文と警告を表示する。原文文字列は常に保持する。

複数文字列をcontents配列で送信し、indexで対応させる。1リクエストの暫定上限は10,000 code points、各文字列は段落・文単位で分割。単純文字切りで識別子を分断しない。公式quotaより小さい値で動作確認して設定する。

## 13.2 失敗と再翻訳

翻訳timeout30秒、retryは429/503に最大1回、1秒backoff。生成済み英語JSONはジョブ完了後もメモリ保持する。再翻訳は同じtranslation入力を使い、再生成しない。途中だけ成功した場合はpartialTranslation=trueとして英語部分を明示する。

## 13.3 認証の配置

GeminiユーザーキーはPUT settingsで受け取り、backendメモリへ保持。demoキーはCHEAPREVIEW_DEMO_GEMINI_KEY、modelはCHEAPREVIEW_DEMO_MODELで設定する。デモモードはキーの自動利用を意味し、キー文字列をブラウザ入力欄へ注入しない。

Cloud Translationはユーザーのデモ用GCP認証を使用。GOOGLE_APPLICATION_CREDENTIALSのローカル認証ファイルとCHEAPREVIEW_GCP_PROJECTをbackendだけで参照する。API有効化・権限・課金設定は導入手順に記載。認証ファイルとキーをZIPに入れない。認証未設定はWebで分かるようにする。

## 13.4 エラー文

GEMINI_AUTH_FAILED：APIキーまたはデモ設定を確認。MODEL_UNSUPPORTED：モデルの構造化出力対応を確認。TRANSLATION_NOT_READY：GCP認証・projectを設定。翻訳の失敗だけで英語生成結果を失わない。

# 14. 計測・比較実験

## 14.1 必須の計測関数

| 関数 | 入出力・意味 |
| --- | --- |
| estimateInputTokens(material) | Unicode分類ごとの文字数、推定tokens、係数版 |
| recordTokenUsage(response) | prompt、candidate、thought、cached、totalのAPI値。未提供はnull |
| measureStageDuration(stage, callable) | 単調時計の経過ms。時刻とは別記録 |
| evaluateSelection(selected, expected) | チャンク・メソッドの正解集合に対する保持率等 |
| runComparisonExperiment(config) | 同じsnapshot・課題・モデルで方式を切替 |
| exportExperimentResults(runId, format) | 生データCSV/JSON、集計値、設定版 |



promptTokenCount等はAPIが返した値を保存し、cached tokensやthought tokensをtotalへ勝手に加算しない。totalは提供値を使う。入力削減はpromptを比較し、出力は別指標とする。

## 14.2 指標

入力削減率=1−T_selected/T_baseline。推定相対誤差=(T_est−T_actual)/T_actual。絶対相対誤差はその絶対値。必須コード保持率=送信した正解メソッド数/正解メソッド総数。任意候補の精度=必要メソッド数/送信メソッド数。分母0はnull。

保持の判定はmethodIdだけでなく、本文の必要行範囲が含まれるかも確認する。署名だけ残して本文を省略した場合は「本文保持」と数えない。必要コード保持は回答品質そのものではないため、回答の正確さ・根拠も手動評価で併記する。

## 14.3 比較条件

| variant | 内容 | 実施 |
| --- | --- | --- |
| FULL | ユーザーが走査許可した評価範囲の全文 | baseline。モデル上限超過はskip、こっそり切らない |
| VECTOR | cosineのみ、同じ予算で選別 | P2 |
| HYBRID | 0.5/0.3/0.2、同じ予算 | 標準 |
| WEIGHT_SWEEP | 0.7/0.2/0.1、0.3/0.5/0.2等 | 係数影響 |
| ESTIMATE_SWEEP | ASCII係数0.25/0.35/0.45、余裕10/20/30% | 推定誤差・予算超過・保持率 |



推定係数実験は同じpromptを再計数して純粋な誤差を比較する段階と、選択結果も変える段階を分ける。重み実験では推定式・予算を固定する。変える要因を混ぜない。

## 14.4 実験実行方針

初回は1課題・1方式を試す。標準の比較は4課題×3方式×3回とし、API呼出の見込み回数を実行前に表示する。maxCallsの設定を超えたら止める。通常利用の会話履歴は使わず、追加質問評価だけ固定の履歴を全方式へ同じように与える。

順序の影響を抑えるためvariant順をseed付きで入れ替える。モデル・設定・snapshot・prompt hash・係数・計測日時を記録する。キャッシュ量や出力長の差は別列に残し、tokens削減をそのまま電力削減と呼ばない。平均・中央値・最小最大を集計し、失敗・skip回数を隠さない。

## 14.5 CSV列

runId,taskId,variant,repeat,seed,modelId,snapshotId,configVersion,promptHash,inputBudgetTokens,estimatedPromptTokens,marginTokens,actualPromptTokens,outputTokens,thoughtTokens,cachedTokens,totalTokens,selectedFiles,selectedMethods,retainedRequiredMethods,totalRequiredMethods,retentionRate,scanMs,selectionMs,generationMs,translationMs,totalMs,translationChars,status,errorCode。

電力任意列：energyWh,avgPowerW,energySource,energyScope,energyQuality,energyUnavailableReason。値がない場合は空欄。0としない。

# 15. 電力計測とColab補助実験（P3）

## 15.1 アプリ側の範囲

EnergyMeter.start(scope)→handle、stop(handle)→EnergyMeasurement。既定実装はNoopEnergyMeterでnullとreason=NOT_IMPLEMENTEDを返す。実装有無によらず通常処理を動かす。測れる端末でセンサーまたは外部計測器アダプターを追加する。

EnergyMeasurementはenergyWh、avgPowerW、durationSeconds、components、source、quality(SENSOR/ESTIMATE)、scope(LOCAL_DEVICE/LOCAL_COMPONENTS/COLAB_COMPONENTS)、sampleCountを持つ。端末全体とCPUのみを同列に比較しない。

## 15.2 積分の暫定式

電力サンプルが得られる場合、E_Wh=Σ((P_i+P_{i+1})/2×Δt_i)/3600。平均W=E_Wh×3600/測定秒。累積エネルギーカウンターが利用可能なら差分を優先し、電力積分で重複加算しない。

測定対象の処理時間を覆う連続サンプルがない場合は不完全として記録する。サンプリングは1秒を初期値とし、短い解析は複数回バッチ実行で測る。アイドル基準を引く場合は総量と差分を両方表示し、負値を隠さず計測誤差として扱う。

## 15.3 Colab notebook構成

前半：アプリのCSVを読み、方式別tokens・保持率・誤差・時間の図を生成。これはP2の補助として軽く実装可能。

後半（任意）：同じ公開LLM・同じハードウェア・同じ質問で、日本語回答と英語回答＋ローカル翻訳を比較。CodeCarbon等で利用可能センサーと推定fallbackを確認し、GPU、CPU、RAMのどこが実測か記録する。翻訳をCPUへ固定できるモデル・ライブラリを実装時に選ぶ。

GeminiやCloud TranslationをColabから呼んでも、外部APIサーバーの消費電力量を取得することはできない。Colab内の公開モデル結果は補助実験であり、本アプリのGemini全体の電力削減実測として扱わない。Colabの機材はセッションごとに異なり得るので比較は同一セッション内で行う。

## 15.4 発表での表現

先行研究：設計の動機として出典・対象モデル・条件を示す。アプリ実験：実際に確認できたトークン数、保持率、時間、端末側計測を示す。公開モデル実験：そのモデル・機材での電力量を示す。

ユーザーが言及した「半分」の論文は本文・条件が未確認。研究結果を引用する前に論文URLまたはPDFを確認する。電力未計測でも、削減率を捏造したりトークンから一律換算しない。電力関係のために提出を止めない。

# 16. 専用サンプルとデモシナリオ

## 16.1 サンプルアプリ

demo-springは受注管理を題材にし、OrderController→OrderService→OrderRepository/OrderEntity、UserService/UserRepository、SecurityConfigを含める。認証はデモ向けの単純な構成とし、レビュー対象となるトランザクション・入力検証・認可設定の課題を意図的に配置する。課題をREADMEに明記し、本番利用向けとはしない。

NotificationService、ReportService、AuditController等の無関係候補を加え、選別の意味が見えるサイズにする。日本語コメントあり／なしを混在。外部DB不要で起動できるH2を使用。解析デモはサンプル自体の起動を必須としない。

## 16.2 固定課題

| taskId | モード・質問例 | 必須根拠の例 |
| --- | --- | --- |
| T01 | Service：注文作成の処理と例外時の問題を説明して | OrderService.createOrder、Repository.save、OrderEntity |
| T02 | Class：このクラスの各メソッドはどう繋がっていますか | 対象型の署名と主要メソッド本文 |
| T03 | Structure：HTTP受付から永続化までの流れを説明して | Controller endpoint、Service、Repository |
| T04 | Auth：管理者向け操作はどこで制限されていますか | SecurityFilterChain、該当Controller |
| T05 | 追加質問：その処理が失敗した場合はどうなりますか | 固定前往復＋例外処理メソッド |



expected-methods.jsonにmethodIdの元となる相対パス・型・signatureと必要行範囲を記載する。サンプル実装後に実ASTのIDへ変換し、存在しない正解定義は実験開始時にエラーにする。

## 16.3 デモ順

start→demoモード→専用フォルダ選択→Service選択→予算不足を表示→予算を増やす→日本語回答→追加質問→関連コードと選択理由→比較結果。発表時のグラフは事前実験を用意し、ライブで大量APIを呼ぶ必要はない。

mockモードは開発の結合確認用としてproviderだけ差し替え可能にする。Webで実API／mockを明示し、mockのtokensや回答を実績として発表しない。デモモードは実API認証の自動利用であり、mockと別設定。

# 17. 例外・制限・基本的な保護

| code | 条件 | 動作 |
| --- | --- | --- |
| BRIDGE_DISCONNECTED | heartbeat期限超過 | フォルダ要求不可、再接続案内 |
| NO_ACTIVE_FILE | 現在エディタなし | targetファイル選択を案内 |
| INVALID_SCOPE | root外・未登録パス | 読取拒否 |
| FILE_CHANGED | hash相違 | 再走査とpreview再取得 |
| PARSE_PARTIAL | Java解析・symbol未解決 | 警告、可能部分継続 |
| BUDGET_TOO_SMALL | mandatory＋余裕が不足 | 最低予算を返し、Gemini呼出なし |
| HISTORY_TOO_LARGE | 最新往復が入らない | 予算増加／新会話 |
| GENERATION_TIMEOUT | 120秒 | FAILED、手動再実行 |
| INVALID_OUTPUT | JSON不正・schema不一致 | FAILED、rawはメモリ保持 |
| TRANSLATION_FAILED | 認証・quota・timeout | 英語表示、再翻訳 |



バックエンドの処理poolは2threads、外部生成の同時実行は1。scan timeout60秒、全job暫定上限240秒、履歴保持最大20往復、job result保持2時間。実験結果は実験終了後にファイルへ保存する。大きなファイルには上限を設け、メモリを無制限に増やさない。

APIキー・認証・コード全文をログに出さない。外部送信はWebの明示送信操作に結び付け、previewや走査で生成APIを呼ばない。翻訳では説明文字列のみ送る。モデル本文やコードのHTMLをそのまま描画せず、Reactのテキスト出力を使う。

Web同一オリジン、localhostバインド、接続トークン、許可root、サイズ上限を基本実装に含める。大規模なログイン・権限管理・監査基盤は追加しない。

# 18. 設定一覧と実装契約

| 設定キー | 既定値 | 変更方法 |
| --- | --- | --- |
| server.address / port | 127.0.0.1 / 8765 | 起動引数。port競合時は明示停止 |
| selection.weights | 0.50 / 0.30 / 0.20 | 設定ファイル・実験variant |
| selection.maxDependencyDepth | 2 | 設定ファイル |
| selection.maxMethodsPerFile | 3＋同型call補完 | 設定ファイル |
| token.ascii / japanese / other | 0.35 / 1.50 / 1.00 | 設定ファイル・実験variant |
| budget.marginRate / minMargin | 0.20 / 512 | 設定ファイル |
| budget.defaultInputTokens | 8192 | Web変更可 |
| model.temperature / maxOutput | 0.2 / 2048 | backend設定。modelIdはWeb |
| history.maxIncludedTurns / ratio | 6 / 0.25 | backend設定 |
| provider.generationTimeout | 120秒 | backend設定 |
| translation.timeout / retry | 30秒 / 1 | backend設定 |
| energy.enabled | false | 任意機能が実装された時のみtrue |



料金テーブルはモデルID、通貨、入力・出力・思考・cache料金、確認日を別設定とする。未設定なら料金表示なし。全設定にconfigVersionを付けてログへ残す。

実装中の変更は、本書のユーザー決定を維持する。計算係数・ライブラリ・timeout等を変更した場合はREADMEのDesign deviationsへ理由・影響・検証を記録する。日英検索翻訳、コード編集、通常の日本語生成切替、クラウド必須化を黙って追加しない。

# 19. 実装順序と受入確認

## 19.1 コーディングエージェントへの作業順

| 段階 | 実装 | 確認 |
| --- | --- | --- |
| 1 | monorepo、DTO、Spring static、health、起動 | Windowsでブラウザ表示 |
| 2 | runtime接続、拡張登録・folder request | WebからVS Codeフォルダ選択 |
| 3 | scan、AST、snapshot、サンプル | ファイル・メソッド一覧 |
| 4 | TF-IDF、graph、chunk、budget、preview | 超過停止と選択理由 |
| 5 | Gemini structured JSON、翻訳、結果 | 実APIで日本語回答 |
| 6 | 4モード、追加質問、再翻訳、cancel | 一連の操作が成立 |
| 7 | 実験・CSV・Colab集計、ZIP | 提出物だけで導入できる |
| 8 任意 | EnergyMeter、Colab公開モデル | 範囲付き電力結果 |



## 19.2 必要な自動テスト

推定：ASCII/かな/漢字/絵文字/改行が1回だけ計数される。予算：境界ちょうどは許可、1不足は不許可、余裕512最小、関連追加後の再計算。選択：mandatory保持、同点順固定、重複区間合併、2hop境界。

解析：オーバーロード区別、未解決型警告、ルート外パス拒否。履歴：往復単位省略、最新版競合、キャンセルで未commit。翻訳：code・refs不変、placeholder欠落時の原文fallback、再翻訳で生成呼出0回。

外部APIはstubで結合テストし、実APIの疎通はデモ環境で1課題を手動確認する。テストのために多数の実API課金を発生させない。

## 19.3 手動受入条件

ZIP展開後、手順通りにWindowsで起動し、Webと拡張が接続する。

Webのフォルダ選択がVS Codeのダイアログを開き、キャンセルしても壊れない。

選択・質問の変更で概算が更新され、空質問・不足予算では送信できない。

4モード全てで専用サンプルから日本語回答と根拠を表示できる。

生成中に質問を編集しても実行中の入力は変わらず、二重送信しない。

追加質問で履歴が使われ、履歴tokensと省略数が見える。

翻訳失敗時の英語表示と再翻訳が動作する。

実APIのtokens、選択方式、時間、正解保持率をCSV出力できる。

電力が未実装でも通常機能・実験・提出ZIPが完成する。

# 20. 参考資料と未確認事項

以下は公式技術資料。係数の根拠論文ではなくAPI・ライブラリの実装確認先である。実装時は固定した依存バージョンに対応する仕様を確認する。

| 対象 | URL |
| --- | --- |
| VS Code API・showOpenDialog | https://code.visualstudio.com/api/references/vscode-api |
| VSIX packaging | https://code.visualstudio.com/api/working-with-extensions/publishing-extension |
| JavaParser | https://javaparser.org/getting-started.html |
| Gemini structured output | https://ai.google.dev/gemini-api/docs/structured-output |
| Gemini token counting | https://ai.google.dev/gemini-api/docs/tokens |
| Cloud Translation text | https://docs.cloud.google.com/translate/docs/translate-text |
| CodeCarbon power methodology | https://docs.codecarbon.io/3.3/explanation/power-estimation/ |
| Colab FAQ | https://research.google.com/colaboratory/faq.html |



未確認事項は実装開始を止めない。①電力半減に関する先行研究の論文・条件（発表で引用する前に必要）、②利用者のWindows機材で電力センサーが取得可能か（P3開始時に確認）、③デモで実際に使用するモデルID・GCP認証・料金（疎通時に設定）。

電力半減を本アプリの保証値にしない。計算式・メソッド選択規則は実装開始用の仮説であり、比較ログを基に調整する。ユーザーに新たな必須の製品判断を求めず、上記の設定可能な暫定値から実装を開始できる。

# 21. 会話TXTの保存・取込・コピペ引継ぎ

本章は追加決定。履歴の手動保存と引継ぎをP0に追加する。DB・ログイン・S3は導入せず、利用者の端末へTXTをダウンロードする。Library保存やクラウド同期を本アプリの機能として設けない。

## 21.1 ダウンロード内容と画面

会話欄に「会話をTXT保存」「会話を引き継ぐ」を配置。保存するUSERはユーザーが入力した質問本文、ASSISTANTはその時点で画面に表示する日本語回答。回答の見出し、指摘、コード、相対パスと行番号、制限事項を読みやすいテキストへ変換して含める。翻訳失敗部分は英語原文と未翻訳の注記を残す。

ここでいうプロンプトはユーザーの質問。内部system文、回答schema、自動添付したソース全文、APIキー、絶対フォルダパスは出力しない。回答に含まれるコード断片は保存する。ユーザーが質問に直接書いた内容は原文で保存する。

ファイル名はcheapreview_conversation_YYYYMMDD_HHMMSS.txt。UTF-8 BOM付き、改行LF、Content-Type=text/plain; charset=utf-8、Content-Disposition=attachment。保存タイムスタンプはAsia/Tokyo。履歴なしでは保存ボタン無効。生成中も確定済み往復だけを保存できる。

## 21.2 書式と衝突回避

先頭にCHEAPREVIEW_CHAT_V1、次行にEXPORTED_AT=<ISO8601>を置く。本文は単独行USER:とASSISTANT:で区切り、各本文は質問・回答の複数行テキストとする。例は下記。本文の行がUSER:またはASSISTANT:と完全一致する場合と、先頭がバックスラッシュの場合は、保存時にバックスラッシュを1つ前置し、取込時に1つ除去する。通常文章の中のUSERという語は区切らない。

CHEAPREVIEW_CHAT_V1

EXPORTED_AT=2026-10-06T23:52:05+09:00



USER:

注文作成の処理を説明してください。

ASSISTANT:

OrderService.createOrderが注文を検証して保存します。

参照: src/main/java/example/OrderService.java:20-45



USER:

その処理が失敗した場合はどうなりますか？

ASSISTANT:

例外処理とトランザクション設定を確認する必要があります。

USER→ASSISTANTの往復順を検証し、途中の空本文・順序不正はimport previewでエラーにする。exportは会話に確定した全往復を出す。生成時の履歴省略とは別で、通常の20往復上限により既に破棄した往復は復元しない。

## 21.3 ファイル取込と貼り付け

「会話を引き継ぐ」はTXTファイル選択またはtextareaへコピペの2通り。まずプレビューし、往復数・文字数・見込みtokensを表示して、利用者が「この履歴で開始」を押して新会話を作る。現在の会話を黙って上書きしない。フォルダ選択は別途必要。

専用書式のTXTはエスケープを復元して往復を抽出。コピペでもヘッダーと専用区切りが揃えば同じ処理。専用書式でない文章は「引継ぎメモ」1件として扱い、任意文章からUSER/ASSISTANTを推測して役割を捏造しない。

最大ファイル1MiB、本文100,000 code points、取込最大20往復。超過時はプレビューで削減を案内し、黙って切らない。UTF-8 BOMを除去、CRLFをLFへ正規化。文字化けや不正書式はエラーで元テキストを保持する。

## 21.4 生成への利用

ImportedContextはtype=TRANSCRIPT/NOTE、text、turns?、source=USER_IMPORTを持つ。日本語TXTだけから英語JSONを復元・再翻訳しない。取り込んだ内容を過去の参考資料として現在のuser promptに区切って添える。system指示や実際のassistant roleへ格上げしない。通常の同一セッション履歴は従来どおり英語JSONで使う。

引継ぎコンテキストも推定・安全余裕・履歴25%枠に含める。TRANSCRIPTは最新往復から最大6往復を採用し、省略数を表示。NOTEは途中で自動切断せず、枠内に入らない場合は利用者が短く編集する。最新版1往復も入らなければ予算増加を案内。追加の英訳APIを呼ばないため、取込履歴は日本語のまま入力に含まれる。

引継ぎは会話の内容を再利用する機能であり、以前のsnapshot・選別結果・実行状態を完全復元する機能ではない。保存TXTのコード参照は新フォルダで存在するか確認し、不一致は参考情報扱い。新しい回答の根拠は今回読み込んだコードから作る。

取込後のexportではTRANSCRIPTの往復をUSER/ASSISTANTとして出し、その後の新しい往復を追記する。NOTEはUSER本文の先頭に「引継ぎメモ（ユーザー取込）」を付けた単独記録として出す。parserはこの先頭NOTE記録のみASSISTANTなしを許容し、通常往復の未完了と区別する。取込状態はアプリ終了時に消えるので、必要なら再度保存する。

## 21.5 APIと受入条件

| Method・Path | 入力 | 出力 |
| --- | --- | --- |
| GET /conversations/{id}/export?format=txt | conversationId | 確定済み履歴TXT |
| POST /conversation-imports/preview | text、inputKind(FILE/PASTE) | importId、type、往復数、文字数、見込みtokens、warnings |
| POST /conversations/import | workspaceId、importId | 新conversationId |



import previewは5分間メモリ保持。ファイル読込はWebで行い、上限確認後にtextをJSONで送る。APIへ任意のローカルパスを渡して読み込ませない。Springのrequest body上限を2MiBとしてUTF-8最大サイズとの整合を取る。importは外部APIを呼ばない。

受入確認：日本語・コード・USER:を含む本文を保存→取込で一致。再起動→再接続→フォルダ選択→TXT取込→追加質問ができる。コピペでも同じ引継ぎが可能。不正順序・大容量は明示エラー。APIキーとsystem文はexportに含まれない。取込文が予算に反映され、importだけでGeminiや翻訳APIを呼ばない。


## word/header1.xml

CheapReview  |  詳細設計 version01


## word/footer1.xml

CheapReview  •  

