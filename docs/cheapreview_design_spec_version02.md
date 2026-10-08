CheapReview 要件定義と基本設計

Spring Bootのコード理解とレビューを支援するVS Code拡張

version02  2026年10月4日

コンセプト: 「高価なコーディングエージェントを常用できない開発者でも、フォルダをまたいだコード理解を効率化する」ことに特化した、読み取り専用のVS Code拡張。Webアプリは配布・導入・ドキュメントのハブとして機能し、実際の解析はVS Code拡張内で行う。

実装方針  TypeScript / VS Code Extension / Gemini API / ローカルCPU翻訳

## version02の変更内容

本書は、配布Webサイトと読み取り専用VS Code拡張からなるCheapReviewの要件と実装方針を定める。従来のSpring Boot分類に、ベクトル検索・依存グラフ・入力予算内のコード選択を加え、必要な情報を保ちながらAIに送る量を減らす。

回答は日本語の質問から英語で生成し、ローカルCPU上の翻訳モデルで説明文を日本語に変換する。入力側と出力側の両方を最適化し、コード理解に必要な品質と、料金・待ち時間・エネルギーの関係を評価する。

採用方針は決定事項として記載する。一方、採用する翻訳モデル、選択スコアの重み、具体的な予算上限、性能目標は実装・比較実験で確定する。電力半減は現時点で製品の保証値にはしない。

# 目次

1. 概要

2. ターゲット層・ペルソナ

3. 課題

4. 解決策・提供価値

5. プロダクト方針とスコープ

6. ユーザーフロー

7. システム構成

8. 実装が必要な機能

9. フォルダ構成

10. フロントエンドイメージ

11. VS Code拡張のUX

12. コード解析・Candidate Selector設計

13. LLM連携設計

14. 技術スタック

15. セキュリティ・APIキー管理

16. コスト最適化設計

17. 非機能要件

18. MVP範囲 / 非対象

19. テスト計画

20. ハッカソンでの見せ方

21. リスクと対策

22. 将来拡張

23. 開発優先順位

24. 補足




# 1. 概要

CheapReviewは、Spring Bootプロジェクトをローカルで解析し、質問との関連度、依存関係、入力予算を考慮して選んだコードだけをLLMに渡すことで、低コストなコード理解とレビューを支援するVS Code拡張である。

初期デモではコードの自動編集や生成は行わず、読み取り専用に限定する。これにより、実装量、安全性、導入障壁を抑えつつ、「フォルダをまたいだ理解」という明確な課題に集中する。

| 項目 | 内容 |
| --- | --- |
| プロダクト種別 | Webアプリ + VS Code拡張 |
| 初期対応フレームワーク | Spring Boot |
| 主要価値 | コード理解、構造把握、レビュー、依存関係の説明 |
| 差別化 | 構造・類似度・依存・予算で必要コードを選び、英語生成とCPU翻訳を組み合わせる |
| 編集権限 | なし（読み取り専用） |
| MVP言語 | TypeScriptのみ |
| LLM | Gemini APIを第一候補。ユーザー自身のAPIキーを利用 |
| 配布方法 | WebページからVSIXを配布 |

日本語の質問に対してLLMは英語の回答を生成し、ローカルCPUの翻訳モデルが説明文を日本語化する。コードや識別子、参照先は原文を保持する。WebサイトはVSIX配布と導入案内を担当する。

# 2. ターゲット層 ペルソナ

## 2.1 プライマリターゲット

「月額課金の高価なコーディングエージェントにはお金を使いたくないが、既存コードを読む時間は短縮したい開発者」。特に学生や個人開発者、若手開発者を中心に想定する。

| 属性 | 想定 |
| --- | --- |
| 代表ペルソナ | 20歳前後・男性・学生開発者 |
| 経験 | Java / Spring Bootを学習中〜実務初級 |
| 開発環境 | VS Codeを日常利用 |
| 困りごと | 他人のコード、昔書いたコード、大きい課題リポジトリの理解に時間がかかる |
| 支払い意欲 | 低い。できれば無料、少なくとも従量課金を最小化したい |
| 行動 | README、検索、定義ジャンプ、ChatGPTへのコピペを組み合わせて理解している |
| 期待 | 少ない操作で、プロジェクト全体のつながりを説明してほしい |

「20歳男性」は初期ペルソナであり、製品自体は性別・年齢を限定しない。ハッカソンでは具体的な人物像を置くことで、機能の取捨選択をしやすくする。

# 3. 課題

既存のコーディングエージェントは高機能だが、その分、月額費用またはAPI利用料が発生しやすい。また、コード生成・編集まで含めた多機能性は、単純に「このプロジェクトを理解したい」利用者にとって過剰な場合がある。

大規模なリポジトリを毎回LLMに渡すと入力トークンが増え、コストが上がる。

フロントエンドの説明が不要なのに、バックエンド調査時まで関連の薄いファイルを送ると無駄が大きい。

フォルダをまたぐ処理フロー（Controller → Service → Repository → Entity）を人力で追うのは時間がかかる。

READMEが古い、または詳細な関数・依存関係まで説明していない場合がある。

コード理解だけが目的でも、一般的なコーディングエージェントは編集・生成機能を含むため導入が重い。

## 3.1 本プロジェクトで解消したい理解負債

本設計では、コード量の増加に伴い「どこに何があり、何が何を呼んでいるかを理解するための時間」が積み上がる状態を「理解負債」と呼ぶ。CheapReviewはこの負債の削減に特化する。

# 4. 解決策 提供価値

Spring Boot固有の役割分類、TF-IDFによるベクトル類似度、依存グラフを組み合わせて候補を採点する。その後、入力トークン予算内で必要なコードを選ぶ。LLMは選択されたコードを根拠に説明・レビューを行い、その英語回答をローカルCPUで日本語化する。

| 従来型の使い方 | CheapReview |
| --- | --- |
| 大きなコードをそのままLLMに投入 | アノテーション・package・import等で候補を削減 |
| 汎用エージェント | Spring Bootに特化 |
| コード生成・編集も含む | 読み取り専用 |
| 月額サービスに依存 | ユーザー自身のGemini APIキーを利用 |
| コストが見えにくい | 対象ファイル数・送信量を画面に表示可能 |

差別化の中心: 売りは「安いモデルを使うこと」だけではなく、「LLMに送る量そのものを減らす設計」にある。

# 5. プロダクト方針とスコープ

## 5.1 初期方針

Spring Boot専用にする。

VS Code専用にする。

コード編集はしない。

Webアプリは配布・導入・ドキュメントに集中する。

VS Code拡張内でリポジトリ走査、候補抽出、Gemini API呼び出し、結果表示まで完結させる。

外部の独立バックエンドサーバーはMVPでは持たない。

ベクトル計算はローカルメモリで行い、外部Vector DBやEmbedding APIを必須にしない。

コード選択の予算管理と、英語生成・CPU翻訳をMVPの技術的な核にする。

## 5.2 なぜ絞るのか

ハッカソンでは対応範囲を広げるより、1つのフレームワークに対して「ちゃんと理解できる」体験を作る方が完成度を出しやすい。Spring BootはController / Service / Repository / Entityなどの役割が明確であり、アノテーションも機械的に拾いやすいため、候補絞り込みのデモに向いている。

# 6. ユーザーフロー

1. ユーザーがWebアプリを開く。

2. 「Spring Boot Agent」を選択する。

3. Webページ上の説明を読み、VSIXファイルをダウンロードする。

4. VS Codeの「Extensions: Install from VSIX...」から拡張をインストールする。

5. Spring BootプロジェクトをVS Codeで開く。

6. 初回実行時にGoogle AI Studioで取得したGemini APIキーを入力する。

7. APIキーはVS Code SecretStorageへ保存する。

8. Command Paletteから「CheapReview: Review Service Layer」などを実行する。

9. 拡張がJavaファイルを分類・ベクトル化し、依存グラフと合わせて候補を採点する。

10. 入力予算内のコードと日本語の質問をGemini APIに送り、英語の構造化回答を生成する。

11. CPU翻訳で説明文を日本語化し、原文・根拠ファイル・利用量と合わせて表示する。

MVPではチャットUIを作らなくてもよい。Command Palette + Output Channelだけで十分に価値検証できる。

12. 翻訳モデル未取得時は、容量と取得元を表示して初回ダウンロードを案内する。翻訳失敗時は英語原文を表示し、再度LLMを呼び出さずに翻訳を再試行できるようにする。

# 7. システム構成

| 構成要素 | 責務 |
| --- | --- |
| 配布Webサイト | Agent一覧、Spring Boot紹介、VSIX配布、導入ドキュメント |
| ローカル解析 | 走査、Spring分類、識別子抽出、TF-IDF、依存グラフ |
| コード選択 | 関連度採点、必須コード確保、入力予算管理、選択理由の記録 |
| Gemini連携 | 必要コードと日本語の質問を送信し、英語の構造化回答を取得 |
| CPU翻訳 | 説明文だけを英日翻訳。コード・識別子・参照先を保持 |
| 結果表示 | 日本語結果、英語原文、根拠、トークン量、処理時間、エラー |

## 7.1 処理フロー

日本語質問 → ローカル走査・分類 → ベクトル類似度と依存関係の採点 → 入力予算内のコード選択 → 英語回答生成 → 説明文のCPU翻訳 → 日本語結果と英語原文の表示

# 8. 実装が必要な機能

| ID | 機能 | 概要 |
| --- | --- | --- |
| F-01 | VSIX配布Web | Spring Boot用VSIXをWebから取得できる。 |
| F-02 | 導入ドキュメント | VSIXの入れ方、APIキー取得方法、操作方法を表示する。 |
| F-03 | ワークスペース検出 | VS Codeで開いているプロジェクトルートを取得する。 |
| F-04 | Repository Scanner | 対象ディレクトリからJavaファイルを走査する。 |
| F-05 | 除外ルール | .git、target、build、node_modules等を除外する。 |
| F-06 | Spring Classifier | @Service、@Repository、@RestController、@Entity等を検出する。 |
| F-07 | Candidate Selector | ユーザーの実行コマンドに応じて対象クラスを絞る。 |
| F-08 | Dependency Finder | import、型、コンストラクタ依存から関連クラスを辿る。 |
| F-09 | Context Builder | 必要コードとタスクだけをLLM入力に整形する。 |
| F-10 | Gemini Client | Gemini APIへリクエストを送信する。 |
| F-11 | SecretStorage | ユーザーのGemini APIキーを安全に保存・取得する。 |
| F-12 | Output | 調査結果をOutput Channel等へ表示する。 |
| F-13 | APIキー管理コマンド | Set / Clear API Keyを提供する。 |
| F-14 | 利用量可視化 | 候補ファイル数、送信対象数、概算文字数/トークン量を表示する。 |
| F-15 | 日本語質問入力 | コマンド実行時に追加質問を入力できる。未入力時は用途別の定型質問を使う。 |
| F-16 | ローカルベクトル化 | 識別子・コメント・アノテーションをTF-IDFでベクトル化する。 |
| F-17 | 依存グラフ | import・型・コンストラクタ依存からプロジェクト内の依存候補を作る。 |
| F-18 | 複合関連度 | 類似度、依存距離、Spring役割を組み合わせて候補を採点する。 |
| F-19 | 入力予算管理 | プロンプト等を含む入力上限を設け、必須コードを確保して追加候補を選ぶ。 |
| F-20 | 英語回答生成 | 日本語入力に対して英語の構造化回答を要求する。 |
| F-21 | ローカルCPU翻訳 | 対応する英日翻訳モデルをローカルCPUで動かし、説明文を翻訳する。 |
| F-22 | 原文保持 | コード・識別子・パス・参照先を保持し、英語原文も閲覧できる。 |
| F-23 | 比較計測 | 選択方式・回答方式ごとのトークン量、時間、品質評価用の記録を出力する。 |

## 8.1 初期コマンド候補

| コマンド | 目的 |
| --- | --- |
| CheapReview: Analyze Project Structure | Controller / Service / Repository / Entityの構造を説明 |
| CheapReview: Review Service Layer | Service層だけをレビュー |
| CheapReview: Explain Current Class | 現在開いているクラスの役割と主要メソッドを説明 |
| CheapReview: Analyze Authentication | Auth / Security / JWT等の関連コードを探索して説明 |
| CheapReview: Set Gemini API Key | APIキー登録 |
| CheapReview: Clear Gemini API Key | APIキー削除 |

# 9. フォルダ構成

提出ZIP全体の推奨構成:

| パス | 内容 |
| --- | --- |
| web-app/src/ | ページ、コンポーネント、スタイル |
| web-app/public/downloads/ | 配布用cheapreview-springboot.vsix |
| vscode-extension/src/ | extension.ts、commands、scanner、analyzer、selector、llm、storage、reporter |
| vscode-extension/src/ の追加 | vectorizer、graph、translation、metrics |
| vscode-extension/ | package.json、tsconfig.json、README.md |
| dist/ | ビルド済みVSIX |
| README.md | 全体の起動・導入・デモ手順 |

## 9.1 VS Code拡張内部

| フォルダ | 責務 |
| --- | --- |
| commands/ | Command Paletteから呼ばれるユースケース |
| scanner/ | ファイル探索、除外判定 |
| analyzer/ | Spring Bootアノテーション、package、import等の解析 |
| selector/ | レビュー対象候補の絞り込み |
| llm/ | Gemini API、プロンプト、Context Builder |
| storage/ | SecretStorage、設定値管理 |
| reporter/ | Output Channel / TextDocumentへの結果表示 |

## 9.2 version02で追加するモジュール

vectorizer/ は識別子分割、検索語展開、TF-IDFと類似度を担当する。graph/ は依存候補の抽出、距離計算、参照解決を担当する。selector/ はこれらの結果と役割分類を受け取り、候補採点と入力予算内の選択を行う。

translation/ はモデルの取得・キャッシュ、CPU推論、説明文の分割翻訳、原文保持を担当する。metrics/ は入力・出力トークン、各工程の時間、選択理由を記録する。翻訳処理はワーカー等に分離し、拡張ホストの操作を妨げない。

LLM連携はGeminiClient、翻訳はTranslatorというインターフェースで分離する。将来モデルを変更しても、解析・選択・表示を大きく変更しない構成とする。

# 10. フロントエンドイメージ

Webアプリ側は処理基盤ではなく、「Agent Store + Documentation」として見せる。ハッカソンのWebアプリ要件を満たしつつ、将来複数フレームワークに展開する世界観を伝える。

## 10.1 トップページ

CheapReview

Understand more. Pay less.

Framework Agents

[ Spring Boot ]   Ready

  Low-cost repository understanding & review

  [Download VSIX] [View Docs]

[ FastAPI ]       Coming Soon

[ Next.js ]       Coming Soon

[ Django ]        Coming Soon

## 10.2 Spring Boot詳細ページ

何ができるか：Serviceレビュー、構造理解、クラス説明、認証周辺調査。

なぜ入力を減らせるか：Spring分類、ベクトル類似度、依存グラフ、入力予算によるコード選択。

導入手順：VSIX → APIキー → Command Palette。

プライバシー：コード編集なし。APIへ送るファイルを限定。

Coming Soon：FastAPI / Next.js等。

回答方式：英語生成後にローカルCPUで日本語化。原文も表示する。

導入条件：翻訳モデルの初回取得とローカル保存が必要。モデル容量と動作条件を案内する。

## 10.3 Web側ではやらないこと

コードアップロード。

リポジトリ解析。

Gemini APIへの代理アクセス。

ユーザーAPIキーの保存。

レビュー履歴の保存。

# 11. VS Code拡張のUX

## 11.1 初回

1. CheapReview: Review Service Layer

2. API Key not found

3. "Gemini API Keyを入力してください"

4. SecretStorageへ保存

5. Scan開始

## 11.2 実行中表示

[CheapReview]

Scanning workspace...

Java files found: 84

Service candidates: 7

Related dependency candidates: 5

Files selected / input budget / actual usage: 実測値を表示

Generating English response / Translating on local CPU...

## 11.3 結果例

=== Service Layer Review ===

UserService

- Role: ユーザー登録・更新処理

- Dependencies: UserRepository, PasswordEncoder

- Findings:

  - RuntimeExceptionを直接利用

  - 類似したfindById処理が複数存在

- Suggestions:

  - Domain-specific exceptionの導入

  - 重複処理の共通化

## 11.4 version02の表示要件

主表示は日本語の役割説明、依存関係、指摘、改善案とする。英語原文を別ドキュメントで開けるようにし、根拠ファイルと行範囲を併記する。行番号は送信時のソースに対応させる。

実行ログには、走査ファイル数、選択ファイル数、選択理由、入力上限、入力・出力トークンの実測値または推定値、ローカル解析時間、API待ち時間、翻訳時間を表示する。推定値は明示する。

通常モードは日本語を直接生成する比較・代替経路とする。CPU翻訳モードでは英語生成後に翻訳する。モデル未取得や翻訳失敗では英語原文を保持し、現在の実行について自動の再生成はしない。

# 12. コード解析 Candidate Selector設計

CheapReviewの最重要部分。汎用LLMにRepository全体を送らず、Spring Bootの構造を使って対象を削減する。

## 12.1 機械的に見る情報

ファイルパス / ディレクトリ名

拡張子（.java）

package宣言

import宣言

クラス名 / interface名

Spring系アノテーション

フィールド型

コンストラクタ引数

代表的なキーワード（Auth / Security / JWT / User / Token等）

## 12.2 Spring Boot分類ルール例

| 判定 | 代表ルール |
| --- | --- |
| Controller | @RestController / @Controller / package名controller |
| Service | @Service / package名service |
| Repository | @Repository / JpaRepository継承 / package名repository |
| Entity | @Entity / package名entity, domain, model |
| Config | @Configuration / package名config |
| Security | SecurityFilterChain / @EnableWebSecurity / security package |

## 12.3 選定ステップ

1. 除外対象を除き、Javaファイル一覧を取得。

2. Spring Bootの役割に分類。

3. コマンドと日本語質問から主対象と検索語を決める。

4. 識別子・コメント等のベクトル類似度と、プロジェクト内の依存関係を計算する。

5. 主対象から1〜2段の依存候補を補完し、Spring役割を含む複合関連度を採点する。

6. 主対象を確保し、入力予算と追加候補の関連度・サイズを使って選択する。

7. 選択理由と未選択の関連候補を記録し、予算を確認してContext Builderに渡す。

MVPでは本格的なAST解析を必須にしない。文字列解析・正規表現・簡易パーサで十分。精度が不足した部分だけ後から強化する。

## 12.4 ベクトルの特徴設計

初期方式はTF-IDFとコサイン類似度とする。全文をそのまま使うのではなく、クラス名、メソッド名、フィールド型、アノテーション、コメントを抽出する。camelCaseとPascalCaseを単語に分割し、小文字化する。一般的なJava構文語などは検索への寄与を抑える。

UserRegistrationServiceはuser、registration、serviceに分割する。検索語は定型コマンドと小さな日本語・英語辞書で展開し、ユーザー登録はuser、register、registration、認証はauth、authentication、security等に対応させる。辞書はユーザーが追加できる構造とする。

TF-IDFは単語の重なりを主に扱い、意味的な同義関係を自動で理解する保証はない。日本語質問と英語識別子の不一致を辞書と依存グラフで補う。空ベクトルでは類似度を0として扱い、ゼロ除算を防ぐ。外部Vector DBは不要とする。

## 12.5 依存グラフと参照解決

頂点はプロジェクト内のクラス、辺は参照元から参照先への依存候補とする。packageを含む完全修飾名で識別し、同名クラスを区別する。importだけでなく、フィールド型・コンストラクタ引数・継承先等も参照する。

主対象からの依存距離に応じて得点を下げる。初期実装は1〜2段の探索と循環検出で十分とする。解析できない参照、外部ライブラリ、動的DI等は未解決として記録し、推測の呼び出し関係を断定しない。importの辺を実行時の呼び出しとして表示しない。

Personalized PageRankは発展候補とする。MVPで必須なのは依存距離を使った補完であり、PageRankの導入は実測で価値がある場合に限る。

## 12.6 複合関連度と選択理由

質問とのコサイン類似度、主対象との依存距離に基づく得点、コマンドに対するSpring役割の適合度を0〜1に揃え、重み付きで合算する。重みは設定値として分離し、固定ルールとの比較で調整する。

Explain Current Classでは現在のクラスを主対象として必ず優先する。Serviceレビューでは予算内で扱えるServiceを選び、未選択Serviceを明示する。Analyze Authenticationでは辞書検索だけでなくSecurity設定やFilterなどの依存候補も補完する。

例として、UserRepositoryを選んだ理由は「UserServiceからの直接依存」、SecurityConfigを選んだ理由は「認証コマンドへの役割適合」と記録する。類似度だけを根拠に全候補を削らない。

## 12.7 入力予算内の選択

入力予算とは、システム指示・質問・メタデータ・コード・整形用文字列を含む入力トークン上限を指す。ファイル数上限は補助制限とし、API料金の予算と消費電力量の予算とは区別する。出力トークン上限は別途設定する。

最初に主対象と説明に必須の型情報を確保する。残りの枠に、関連度と追加の情報量を入力サイズで評価した候補を順に追加する貪欲法を初期実装とする。組合せの厳密な最適解は保証しない。

MVPの選択単位はファイルまたはクラス単位とする。メソッド単位の抽出は簡易パーサで境界を安全に取れる場合に導入し、宣言・必要な型情報・元ファイルの行範囲を残す。途中でコードを機械的に切断して予算に合わせない。

必須コードだけで予算を超える場合はAPIを呼ばず、対象の絞り込みまたは予算の変更を案内する。送信直前に入力量を再確認し、推定しかできない場合は余裕を確保して推定値と明示する。

類似コードの重複ペナルティは追加候補に対する発展機能とする。対象コードや必要な依存先を類似しているだけで削除しない。重複レビューでは、似た実装同士を比較できるように用途に応じてペナルティを変える。

# 13. LLM連携設計

## 13.1 LLMにやらせること

クラスやメソッドの役割説明。

複数ファイルをまたいだ処理フローの自然言語化。

可読性・責務・例外処理などのレビュー。

改善案の生成。

## 13.2 LLMにやらせないこと

全ファイル探索。

不要ディレクトリ除外。

@Service等の単純分類。

package / import抽出。

ファイル読み込み自体。

## 13.3 Contextフォーマット例

Task: Service層をレビューしてください。説明文は英語で回答してください。

Project Summary:

- Framework: Spring Boot

- Selected files: 7

Target:

- UserService.java

Related:

- UserRepository.java

- User.java

Source:

<selected source code>

Output: 構造化JSON。説明文は英語、識別子とコードと参照先は原文を保持。

1. Role

2. Dependencies

3. Findings

4. Suggestions

## 13.4 英語生成とCPU翻訳

ユーザーの日本語質問はそのままLLMに渡し、回答の説明文を英語で生成する。入力全体の英訳は必須にしない。受信した構造化回答を検証し、説明文だけをローカルCPU上の翻訳モデルに渡す。

翻訳対象はsummary、roleDescription、findingDescription、suggestionDescriptionなどの人間向け説明とする。symbol、filePath、lineStart、lineEnd、code、dependenciesの識別子は対象外とし、元の値で表示する。説明文に混ざった識別子はプレースホルダーで保護して復元し、復元できなければ原文を保持する。

指摘ごとに根拠ファイル・行範囲を持たせ、参照先が送信済みコードの範囲内か検証する。根拠不足の場合は断定を避け、未確認事項として表示する。JSONが不正な場合は原文を表示し、自動の追加LLM呼び出しは行わない。

## 13.5 翻訳モデルと実行環境

TypeScriptから利用できるCPU推論環境を採用候補とする。Transformers.jsとONNX Runtimeを候補に、英日翻訳対応、モデル形式、再配布・利用ライセンス、メモリ、初回取得容量、対象OSでの動作を確認してモデルを決定する。特定モデル名や量子化方式は未確定とする。

翻訳モデルは初回取得後にローカルへキャッシュする。MVPはデスクトップ版VS Codeを対象に検証し、Windowsを優先する。Remote環境では拡張が動くホスト側でCPU翻訳が実行され得るため、実行場所を明示し、VS Code Webを対応済みとは扱わない。

モデル読み込みと翻訳の時間を分けて計測する。長い説明は文・段落単位で分割し、モデルの入力長制限を超える切り捨てを防ぐ。翻訳中のキャンセルと失敗時の英語原文表示を設ける。

## 13.6 回答品質を守る要件

翻訳前後で指摘件数、識別子、コード、ファイルパス、行番号を一致させる。否定、条件、例外の意味が維持されているかを評価し、Transaction、Repository、Entityなどの用語は表示方針を揃える。

英語生成が常に日本語生成より安い、速い、正確とは仮定しない。同じモデルとタスクで回答品質、出力トークン、待ち時間を比較し、翻訳による追加の誤りも評価する。

# 14. 技術スタック

| 領域 | 採用候補 | 理由 |
| --- | --- | --- |
| Web | TypeScript + 任意の軽量Webフレームワーク | VS Code拡張と同じ言語に統一しやすい |
| VS Code Extension | TypeScript | VS Code APIとの親和性が高く、VSIX配布しやすい |
| コード解析 | TypeScript | MVPでは正規表現・文字列解析で十分 |
| LLM | Gemini API | 無料枠を利用できる可能性があり、ユーザー負担を小さくできる |
| APIキー保存 | VS Code SecretStorage | settings.jsonやソースへの平文保存を避ける |
| 結果表示 | VS Code Output Channel / TextDocument | Webviewを作らず実装量を抑える |
| 配布 | VSIX | Marketplace公開なしでもインストール可能 |

Gemini APIの無料枠・レート制限・対象モデルは変更される可能性があるため、デモ前にGoogle公式情報で最新条件を確認する。

## 14.1 version02の追加スタック

ベクトル化・類似度・グラフ探索・予算選択はTypeScriptで実装する。TF-IDFは小規模な実装または対応ライブラリを用い、比較実験で同じ前処理を固定する。翻訳はTransformers.js / ONNX Runtimeを候補とし、対象モデルが利用可能であることを確認する。

Webは既存のTypeScript構成を維持する。推論用の独立WebサーバーやPythonサービスはMVPで必須にしない。エネルギー測定用の実験スクリプトは、製品本体とは別の評価用ツールとして扱う。

# 15. セキュリティ APIキー管理

## 15.1 APIキー

ユーザー自身が取得したGemini APIキーを初回利用時に入力し、VS Code SecretStorageへ保存する。CheapReview運営側の共通キーはVSIXに埋め込まない。

APIキーをソースコードへハードコードしない。

.envをユーザーに手作業させない。

settings.jsonに平文保存しない。

Clear API Keyコマンドを提供する。

ログにAPIキーを出力しない。

## 15.2 コードデータ

必要なファイルだけをAPIへ送る。

送信予定のファイルと、予算で除外した関連候補を実行ログに表示する。

MVPでは外部サーバーにコードを保存しない。

コードの自動変更・削除を行わない。

翻訳モデルは取得元とライセンスを確認し、ローカル保存する。翻訳時にコードや回答を追加の外部翻訳サービスへ送信しない。

APIキー、秘密情報、不要な設定ファイルを送信対象に含めない。API提供側のデータ取扱いは導入ドキュメントから確認できるようにする。

# 16. コスト最適化設計

API利用料はモデルごとの入力単価・出力単価とトークン量、追加呼び出しの有無に依存する。入力側はコード選択、出力側は英語生成・CPU翻訳で削減を目指す。英語生成の料金効果は実測して示し、無料枠や具体的な単価を固定の保証としない。

| 施策 | 内容 |
| --- | --- |
| モデル | 低価格/無料枠を使えるGeminiモデルを候補にする |
| 入力削減 | Spring分類・ベクトル類似度・依存グラフ・入力予算で候補を選ぶ |
| 依存深度制限 | 関連クラス探索を1〜2階層に制限 |
| ファイル上限 | 1回に送るファイル数の最大値を設定 |
| キャッシュ | 将来、ファイルハッシュ単位で解析結果を再利用 |
| 用途特化 | コード生成をしないため会話の往復を減らせる |

## 16.1 デモで見せたい数値

Repository files: 318

Java files: 84

Candidate files: 12

Files sent to LLM: 7

Estimated full-scan input: 145,000 tokens

Optimized input: 11,200 tokens

Reduction: 92.3%  ※デモ用の例。実測値を表示する。

## 16.2 エネルギー評価の方針

日本語入力・英語出力・CPU翻訳で消費電力量を約半分にできるという研究知見を、組み込みの動機とする。参照研究の書誌情報、モデル、機器、タスク、測定範囲は評価時に記録し、CheapReviewでの達成率と区別する。

比較対象は日本語を直接生成する方式と、英語生成後にCPU翻訳する方式である。後者はLLM生成とCPU翻訳の合計消費電力量で比較し、同じ意味を含む回答品質を満たす条件で判定する。モデルの初回ロードを含む計測と、ロード後の定常状態の計測を分ける。

Gemini API経由では通常サーバー側の実消費電力量を取得できないため、APIデモではトークン量と時間を表示する。実電力量の評価は測定可能なローカルLLM環境等で別途行う。そこで得た結果をGeminiでの電力削減率として表示しない。

電力はW、消費電力量はJまたはWhとして区別する。翻訳CPUだけの値でシステム全体の削減を主張しない。電力量を実測できない場合は未測定とし、トークン削減率を電力削減率に置き換えない。

# 17. 非機能要件

| 分類 | 要件 |
| --- | --- |
| 性能 | 数百Javaファイル規模の走査はユーザーが待てる時間内に完了する |
| 安全性 | 読み取り専用。ファイル編集・削除コマンドを持たない |
| 導入性 | VSIXインストール + APIキー入力のみ |
| 可搬性 | 一般的なVS Code環境で動作する |
| 可観測性 | 選択ファイル数・解析ステップ・エラーをOutput Channelへ表示 |
| 障害時 | APIキー不正、レート制限、ネットワーク失敗を人間向けメッセージにする |
| 保守性 | framework-specific ruleを分離し、将来FastAPI等を追加可能にする |

## 17.1 追加の非機能要件

UI応答性：CPU翻訳を拡張ホストの操作から分離し、進捗・キャンセルを提供する。解析、API、モデルロード、翻訳を別々に計測する。

資源管理：モデルのディスク容量と必要メモリを導入時に示す。未取得・容量不足・未対応OSでは英語原文表示または次回から通常モードを選択できるようにする。

再現性：比較記録にモデル識別子、選択方式、前処理、重み、予算、質問、対象リポジトリの版を残す。説明のないダミー数値や未測定の電力表示は出さない。

# 18. MVP範囲   非対象

## 18.1 MVPに含める

Spring Bootプロジェクトの走査

Service / Controller / Repository / Entityの分類

Service Layer Review

Current Class Explanation

Gemini APIキー登録・削除

Output Channel表示

VSIX配布Webページ

導入ドキュメント

日本語質問入力と用途別検索語展開

ローカルTF-IDFとコサイン類似度

依存グラフと複合関連度による選択

入力トークン予算の管理と選択理由の表示

英語の構造化回答とローカルCPUによる英日翻訳

コード・識別子・参照先の保持と英語原文表示

通常モードとの比較記録

## 18.2 MVPではやらない

コード自動修正

Git commit / PR作成

Marketplace公開必須化

ユーザーアカウント

クラウドバックエンド

レビュー履歴保存

外部Vector DB・Embedding APIを必須とする構成

全言語・全フレームワーク対応

高度なAST・型推論

独自チャットUI / Webview

Personalized PageRank、MMR等の重複抑制、メソッド単位の高度な抽出は発展候補とし、MVPの完走を優先する。

# 19. テスト計画

| 対象 | 確認内容 |
| --- | --- |
| Scanner | 除外ディレクトリが正しく無視される |
| Classifier | @Service等が期待通り分類される |
| Selector | ServiceレビューでController等を無駄に選ばない |
| Dependency Finder | UserService → UserRepository等が辿れる |
| SecretStorage | APIキーが保存・再利用・削除できる |
| Gemini Client | 成功 / 401相当 / レート制限 / タイムアウト時の挙動 |
| Reporter | 長い結果でもVS Code上で読める |
| E2E | VSIX導入→APIキー→実行→結果表示まで通る |

## 19.1 デモ用サンプル

小さすぎるプロジェクトでは「候補削減」の価値が見えにくいため、Controller / Service / Repository / Entity / Securityを含む20〜50ファイル程度のSpring Bootサンプルを用意する。

## 19.2 選択方式の比較

同じ質問・同じモデル・同じ出力上限で、全コード送信、従来のSpring分類と固定依存深度、version02の複合選択を比較する。全コードがモデル上限を超える場合は比較可能な小規模サンプルを使い、比較条件を明示する。

正解となる必要ファイルを人手で定め、必要ファイルの選択率と不要ファイルの混入を測る。入力トークン削減率、ローカル選択時間、回答の正確さ、根拠の妥当性を合わせて評価する。入力削減率だけで成功としない。

辞書展開、依存グラフ、予算選択の各要素を外した比較も行い、どの工夫が必要情報の保持に寄与したかを確認する。

## 19.3 回答方式と故障時の検証

日本語直接生成と英語生成・CPU翻訳を、同じ質問群で複数回比較する。指摘の正確さ、必要事項の網羅、翻訳誤り、出力トークン、API時間、翻訳時間、合計待ち時間を記録する。

コードブロック、識別子、パス、行番号が翻訳前後で一致することを検証する。否定・条件付き指摘を含む例、空ベクトル、同名クラス、循環依存、必須コードの予算超過、モデル未取得、翻訳失敗、不正JSONも確認する。

受入条件は、予算内の送信または超過時の停止、根拠の追跡、保護対象の完全保持、翻訳失敗時の原文保持、削減量と品質を比較可能な記録が出力できることとする。数値の性能目標は初回計測で確定する。

# 20. ハッカソンでの見せ方

## 20.1 デモストーリー

1. Webサイトを開き、Spring Boot AgentとComing Soonの他FWを見せる。

2. 「Download VSIX」から導入できることを示す。

3. VS CodeでSpring Bootプロジェクトを開く。

4. Service Layer Reviewを実行する。

5. 同じ質問で従来ルールとversion02を比較し、選択理由・入力量・必要コードの保持を示す。

6. 英語回答をCPUで日本語に翻訳し、コードと根拠が保たれていることを示す。

7. 入力量、出力トークン、翻訳時間、回答品質を比較する。電力量は別途実測できた範囲のみ説明する。

## 20.2 一言ピッチ候補

必要なコードを入力予算内で選び、英語生成とCPU翻訳を組み合わせて、低コストなコード理解を支援するVS Code拡張。

# 21. リスクと対策

| リスク | 対策 |
| --- | --- |
| Spring Boot構造が標準形でない | annotationとpackageの複数シグナルで判定 |
| 依存関係を取りこぼす | MVPは簡易解析と明示し、関連ファイル候補をログ表示 |
| LLM回答が不正確 | 対象ソースを限定し、役割/根拠ファイルを出力させる |
| 無料枠が使えない/制限到達 | エラー表示、モデル設定変更を可能にする |
| VSIX導入がわかりにくい | Webに画像付き手順を掲載 |
| APIキー漏えい | SecretStorage利用、ログ非表示 |
| Webと拡張が疎結合 | 意図的な構成として説明。WebはAgent Store/Docs、拡張が実行本体 |
| 日本語質問と英語識別子が一致しない | 用途別検索語と小さな辞書で補い、未登録語の限界を明示する。 |
| 予算で必要情報を落とす | 主対象を優先し、未選択の関連候補を表示。超過時はAPIを呼ばない。 |
| 翻訳がコードや条件を変える | 構造化回答、識別子保護、原文保持、技術文の品質評価を行う。 |
| CPU翻訳が遅い・重い | モデルと量子化を比較し、処理を分離。英語原文と通常モードを提供する。 |
| 電力半減を一般化してしまう | 研究条件と製品実測を区別。APIのトークン量から電力量を断定しない。 |

# 22. 将来拡張

FastAPI版：APIRouter / Depends / Pydantic / service層を解析。

Next.js版：app router / server actions / API routes / component依存を解析。

Django版：views / models / serializers / urlsを解析。

Framework自動判定。

複数モデル切り替え。

ローカルLLM対応。

解析キャッシュ。

Git diffレビュー。

Pull Requestレビュー。

依存関係グラフ表示。

レビュー結果をMarkdown/TXTとして保存。

VS Code Webviewによる専用UI。

Personalized PageRankによる依存グラフの関連度計算。

MMRを参考にした重複抑制と、用途別の選択方針。

メソッド単位の安全な抽出と、予算内での情報量の最適化。

翻訳モデル切り替え、技術用語辞書、翻訳品質評価の強化。

## 22.1 拡張しやすい内部設計

analyzer/

├─ common/

├─ springboot/

├─ fastapi/

└─ nextjs/

# 23. 開発優先順位

| 優先 | 実装 | 理由 |
| --- | --- | --- |
| P0 | VS Code ExtensionのHello World / Command登録 | まずVSIXが動く状態を作る |
| P0 | Scanner + Spring Classifier | 本プロダクトの核 |
| P0 | Service Layer Review | デモのメインシナリオ |
| P0 | Gemini API + SecretStorage | 実際のレビューを成立させる |
| P0 | Output Channel | 最小UI |
| P0 | Dependency Finder | フォルダ横断理解の価値を強める |
| P1 | Web配布ページ | ハッカソン要件・世界観 |
| P1 | 利用量表示 | 安さを数値で見せる |
| P2 | Current Class Explanation | 使い勝手向上 |
| P2 | Authentication Analysis | デモ映え |
| P3 | FastAPI/Next.js Coming Soonページ | 将来性の演出 |
| P0 | TF-IDF + 日本語英語辞書 + 依存補完 | 入力側の独自技術の核を実装 |
| P0 | 入力予算選択 + 選択理由 | 削減と必要情報の保持を同時に示す |
| P0 | 英語構造化回答 + CPU翻訳 | 今回採用した出力側の方式を完走 |
| P0 | コード保持 + 翻訳失敗時の原文表示 | 技術回答を安全に表示 |
| P1 | 比較モード + 品質と利用量の計測 | 技術的工夫の効果を実測 |
| P2 | PageRank / MMR / メソッド抽出 | 基本方式で効果が不足した部分を強化 |

## 23.1 version02の実装順序

最初に走査・Spring分類・Gemini呼び出し・表示までを通す。その後、TF-IDFと依存距離、入力予算選択を順に追加し、固定ルールとの比較を可能にする。

CPU翻訳は早期に小さな技術文で動作確認し、モデル形式・英日対応・対象OS・速度を確認してから本体に統合する。構造化回答と識別子保護を加え、最後に比較計測と配布を整える。

# 24. 補足

## 24.1 VSIXとは

VSIXはVS Code拡張を配布するためのパッケージファイル。URLそのものではなく、拡張機能のコードやpackage.json等をまとめたインストール用ファイルである。Marketplaceに公開しなくても、ユーザーはVS CodeからVSIXを選択してインストールできる。

## 24.2 バックエンドがないことについて

MVPでは独立したWeb APIサーバーを置かない。これは欠点ではなく、コストと実装量を抑えるための意図的な設計である。Agentのバックエンド的ロジック（走査・解析・候補選定・LLM呼び出し）はVS Code拡張内部に存在する。

## 24.3 企画の中心メッセージ

CheapReviewは「何でもできるAI開発環境」を目指さない。「コードを書き換えなくていい。まず理解したい」という場面に限定し、フレームワーク特化ルールと最小コンテキストで、安く・速く・安全に理解を助ける。

## 24.4 version02の独自性

TF-IDF、コサイン類似度、グラフ探索、CPU翻訳はいずれも既存技術である。独自性は、Spring Boot向け特徴設計、依存関係と予算を組み合わせたコード選択、識別子を保持する翻訳処理、品質と利用量を同時に評価する設計に置く。

## 24.5 ハッカソン提出物の最小セット

Webアプリ（Agent一覧 + Spring Boot詳細 + Docs + VSIX配布）

Spring Boot用VS Code拡張

配布用VSIX

デモ用Spring Bootプロジェクト

ルートREADME（起動・デモ手順）

比較用の質問群・必要ファイル一覧・計測結果

翻訳モデルの取得手順・ライセンス・動作条件

電力量を測定した場合は、機器・モデル・測定範囲と研究の出典

## 24.6 技術資料と未確定事項

以下は設計上の参考資料。研究の電力半減に関する出典は、採用モデル・機器・タスク・測定範囲とともに評価時に確定する。

TF-IDFと類似度  https://scikit-learn.org/stable/modules/generated/sklearn.feature_extraction.text.TfidfVectorizer.html

Personalized PageRank  https://networkx.org/documentation/stable/reference/algorithms/generated/networkx.algorithms.link_analysis.pagerank_alg.pagerank.html

MMR  Carbonell and Goldstein  The Use of MMR and Diversity-Based Reranking in Document Reranking and Summarization  https://kilthub.cmu.edu/articles/journal_contribution/6610814

Transformers.js  https://huggingface.co/docs/transformers.js/en/index

Node.jsのONNX実行  https://huggingface.co/docs/transformers.js/en/api/backends/onnx

未確定項目は、翻訳モデルとライセンス、予算の既定値、出力上限、スコアの重み、数値の性能目標、研究出典と電力量評価環境である。各項目は比較実験と対象OSでの動作確認を経て確定する。


## word/footer1.xml

CheapReview  version02  |  

