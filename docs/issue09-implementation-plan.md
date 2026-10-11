# Issue #9 会話・TXT機能の実装方針

2026-10-11時点の方針整理。実装・動作確認・外部API通信は行っていない。独立worktreeの `origin/main`（`aa2b99a09bcbc2c58e9cb4928bcef1313c42b198`）を確認した。#14（PR #17）は未マージ、#8の翻訳は未実装。実装は前提PRのマージ後に最新mainから開始し、この計画のコード位置を照合し直す。

## 要件と現状

Issue #9本文の会話メモリ保持、追加質問、USER/ASSISTANT TXTダウンロード、ファイル・貼付取込、容量・形式検証、clearを対象とする。本文・コメントの情報は親エージェントから共有され、コメントは0件。仕様根拠は最新詳細設計version02の第5・10・11・17・21章とAGENTS.md。特に第21章を通読した。

| 根拠・確認位置 | 実装済み | #9で必要な変更 |
| --- | --- | --- |
| `backend/.../budget/service/PromptMaterializer.java:36` | 初回質問用 `HISTORY: none`、共通system/schema/コード材料 | 会話を固定captureとして共通材料へ一度だけ追加 |
| `backend/.../budget/service/BudgetPreviewService.java:76`、`:102`、`:136` | preview・digest・安全余裕・期限の照合。history値は0 | 会話versionと採用履歴の照合・履歴枠・省略数 |
| `backend/.../budget/dto/BudgetPreview.java:11`、`:58` | Requestに会話IDなし。ResultにhistoryTokens/historyTurnsIncludedあり | conversationId/versionとhistoryTurnsOmitted等の契約 |
| `backend/.../generation/service/GenerationService.java:67`、`:155`、`:198` | 入力固定、requestId冪等性、キャンセル、英語DTOと参照検証 | 成功結果の会話commit、clear競合、履歴重複防止 |
| `backend/.../generation/dto/GenerationDtos.java` | 英語構造化Answer、参照、実行質問・workspace・snapshot | 会話ID/version、表示回答契約との接続 |
| `web/src/features/generation/GenerationPanel.tsx:273` | 英語回答描画 | 確定会話表示、保存・引継ぎ・新しい会話の小規模追加 |
| `web/src/features/budget/BudgetPanel.tsx:236`、`previewState.ts` | 入力変更でpreview破棄、履歴tokens表示枠 | 会話変更をrevision/currentResult判定へ追加 |

`backend/...` は `backend/src/main/java/jp/cheapreview/` を表す。会話controller/store、TXT codec/import APIは現mainに存在しない。ブラウザのHistory APIは会話履歴ではない。#14のsourceラベルやエラー詳細はマージ後に維持する。

## データとAPIの接続契約

機能単位の `conversation/controller, service, dto, model, store` を必要分だけ作る。DB、ログイン、S3、自動ファイル保存、localStorageは追加しない。

- Conversation: conversationId、workspaceId、version、確定Turnの不変リスト、latestMode、ImportedContext。最大20往復を保持し、古い往復を破棄する。workspaceを跨ぐ会話IDは拒否する。モード変更は継続、新フォルダでは新会話。
- Turn: turnId/jobId、質問原文、mode、英語Answer、当該回答の参照表、表示回答、表示言語・未翻訳状態。前回の添付コード全文やキー、system/schemaは保存しない。snapshotIdは参考情報であり、旧コードを根拠として復元しない。
- ImportedContext: type=TRANSCRIPT/NOTE、text、turns（TRANSCRIPTのみ）、source=USER_IMPORT。英語JSON・assistant roleを推測復元しない。通常会話の英語Answerとは別型にする。
- GET会話状態はversion・確定履歴・件数を返す小さな追加APIを用意する案。設計に明記された作成・削除・exportと統一し、キー/送信全文を応答に含めない。

| API（`/api/v1`） | 契約 |
| --- | --- |
| POST `/conversations` | workspaceId → conversationId/version。初回生成前に作成 |
| DELETE `/conversations/{id}` | 204、履歴破棄。Webは新しい会話を作り直す |
| GET `/conversations/{id}/export?format=txt` | 確定済み履歴のみ。生成中も使用可 |
| POST `/conversation-imports/preview` | text、inputKind(FILE/PASTE) → importId、type、往復数、code point数、見込みtokens、warnings。5分保持 |
| POST `/conversations/import` | workspaceId、importId → 新conversationId/version。既存会話を上書きしない |
| POST `/analysis/preview`、`/analysis/jobs` | RequestにconversationIdを追加。previewにconversationVersion、historyTurnsOmittedを追加 |

importIdは内容を固定して保持する。期限切れ・存在しないworkspace・消費後の再利用を定型codeで拒否し、日本語の修復操作を示す。import preview自体は外部APIを呼ばない。会話・import総数にも設定可能なメモリ上限と期限を設け、無制限蓄積を防ぐ（値は実装時に明記）。DTOのtoStringで質問・回答をログへ漏らさない。

## 履歴予算・整合性

既存推定係数・安全余裕式は変更しない。初回の履歴0条件を保ち、履歴を共通PromptMaterializerへ一度追加した完成材料で再計数する。コード・質問・system/schemaと履歴の重複計上を避ける。historyTokensは履歴を実際に連結した部分の寄与、総tokensは完成材料の推定値とし、境界の丸めによる差をテストする。

通常会話は最新から最大6往復、入力予算の25%以内で往復単位で採用し、古いものから省略する。安全余裕込みの全体予算も同時に満たす。履歴を確定してから任意関連コードを追加し、主選択を勝手に落とさない。最新1往復も入らない場合は `HISTORY_TOO_LARGE` とし、予算増加/新会話を案内する。NOTEは全文採用またはエラーで、途中切断しない。通常履歴と取込履歴は同じ25%枠に数える。採用数・省略数は通常/取込を区別して表示し、古い順に削除した履歴の再送を防ぐ。

開始時に会話version・採用履歴・質問・コード・設定を不変captureにする。digestにconversationId/versionおよび固定材料を加え、preview期限・会話変更・clear・import・snapshot変更で旧previewを409にする。生成時にlive会話を読み直してpreviewと異なる材料を送らない。

成功状態への遷移と会話commitを同一の整合性境界で行う。jobId/requestIdごとのcommitは1回だけ。キャンセル/timeout/不正JSON/参照不正/生成失敗はcommitしない。応答待ち中のclear/deleteでは削除済み会話を再作成せず、結果を別会話へ混入させない。複数storeの個別synchronizedだけに依存せず、ロック順を固定し、外部通信中にロックを保持しない。成功直前のcancel/clear競合を回帰テストする。

英語生成成功なら翻訳失敗でも1往復commit可能。翻訳更新は同じturnの表示回答だけを更新し、会話版を進めるが重複往復を追加しない。履歴へ再送するのは英語Answer。#8前は英語を表示し、TXTにも英語原文と「未翻訳」を明記する。日本語結果を完成扱いしない。

指示語だけの追加質問は原文検索を維持し、主選択・前回参照・最大2hop依存で補完する。前回参照は現snapshotに存在するfileId/相対パスのみ再解決し、root外や走査対象外を読み直さない。解決できない参照はlimitations/warningsへ。検索式や日英辞書は変更しない。

## TXTの厳密な扱い

UTF-8 BOM付き、LF、`text/plain; charset=utf-8`、attachment、ファイル名 `cheapreview_conversation_YYYYMMDD_HHMMSS.txt`。保存時刻のみAsia/Tokyo、API日時はUTC ISO8601。先頭 `CHEAPREVIEW_CHAT_V1`、次行 `EXPORTED_AT=<ISO8601>`、単独行USER:/ASSISTANT:で区切る。本文が区切り行と完全一致するか先頭バックスラッシュなら1つ前置し、parserは1つだけ戻す。普通の文章中のUSERは区切らない。

見出し・指摘・回答内コード断片・相対パス/行番号・limitationsを表示回答と同じ材料からテキスト化する。React DOM/HTMLの抽出を正とせず、共有の表示用formatterで改行・code blockを保持する。BOM除去とCRLF→LF以外の勝手なtrim・空行削除はしない。区切り直前の改行と本文末尾改行の所有をcodec契約で固定し、空行・末尾改行を含むroundtripで検証する。設計例の見栄え用空行を全て本文へ挿入しない。

USER→ASSISTANTの順序、空本文、不正header/time、末尾未完了を検証する。専用headerを名乗る壊れたTXTをNOTEへ降格しない。専用書式でない任意文章はNOTE1件とし、役割推定しない。NOTEの再exportは先頭USER本文に「引継ぎメモ（ユーザー取込）」を付け、**その先頭記録だけ**ASSISTANTなしを許容する。予約prefixとの衝突・NOTE＋通常往復・NOTE再取込の冪等性はcodecテストで固定する（一般USERが同prefixを手入力した場合の識別は、実装時に仕様注記が必要）。

最大ファイル1MiB、本文100,000 code points、最大20往復。Webはファイルbyte上限を先に確認しUTF-8をfatal decode、Springでもtextの容量/文字数/順序を検証する。貼付も同じcode point上限を適用する。黙って切らず元テキストを保持し、日本語エラーで削減を案内する。APIに任意ローカルファイルパスを渡さない。

設計のHTTP body上限2MiBはJSONエスケープでファイルbyte数より大きくなる点を明示して検証する。制御文字/バックスラッシュが多い入力は上限を超え得るため、2MiBを維持したまま413を分かりやすく表示し、1MiB以内なら必ず送信できるとは保証しない。Spring MVCのmultipart上限だけでJSON bodyを制限したと誤認せず、実際のJSON読み込み前に上限を適用する。

## 秘密情報の扱い

exportの許可項目から組み立てる。settings/credentials、system、schema、全prompt、自動添付ソース、絶対root、provider生エラーは含めない。既存 `generation/service/SecretRedaction.java` は生成生回答のキー置換であり、TXT全体の秘密漏えい防止を保証しない。現在設定中の既知キーはexport/import-preview応答の安全化でも確認するが、認証JSON/.envを読んで秘密一覧を作らない。

設計21.1は「ユーザーが質問に直接書いた内容は原文保存」。一方、AGENTSはキーをTXTへ保存禁止。ユーザーがキーを質問へ貼った場合は原文保存と衝突するため、既知キー一致/既存秘密判定でexportを拒否し、編集を案内する暫定案（黙ってマスクしてroundtrip成功とは主張しない）。不明な秘密を完全自動検出できる保証はせず、保存・引継ぎ画面に利用者の確認を促す。これは実装前に親が現行OutboundSanitizer方針と照合すべき点。キーをエラー本文・ログ・TXTへ再掲しない。

## テストと進め方

1. codec単体：日本語、emoji/surrogate、改行、空行、コード、USER:/ASSISTANT:、先頭バックスラッシュ、末尾改行、BOM/CRLF、NOTE例外のroundtrip。不正順序・空本文・壊れた専用header・UTF-8不正・各上限の直前/ちょうど/超過。
2. store/commit：workspace隔離、20往復破棄、mode継続、新会話、version競合、重複requestId、翻訳再更新、キャンセル/clearとの競合、生成失敗・参照不正で未commit。
3. budget/materializer：25%/6往復/最新1往復境界、NOTE全体不足、安全余裕、main選択保持、二重計上なし、取込日本語がuser資料として連結、system/assistantへ昇格しない、旧preview409。
4. stub結合：通常生成→追加質問→export→import preview→新会話→再preview→生成。importのみのprovider呼出0、キャンセル0commit、キー/system/全コード非export。前回参照の消失・再走査で現コードだけを使う。
5. Web：保存無履歴disabled、生成中は確定往復保存可、貼付/ファイルの同じpreview、失敗時原文維持、確認前に既存会話不変、入力/会話/snapshot変更で古い結果破棄。
6. Spotless、Web format:check/lint/typecheck/test/build、Maven verify、既存Windows CI。実ブラウザ/VS Code再接続/Windows download名・BOMは実操作未確認として分ける。追加の実API実行は不要。

変更候補は新conversation機能・codec・tests、および既存BudgetPreview/Service/PromptMaterializer、GenerationService/DTO、Web types/client/errorPresentation/previewState/BudgetPanel/GenerationPanel、設定例、README、検証記録。#14作業中はこれらを編集しない。#8前提の翻訳済み表示は接続契約だけ用意し、#8がマージされたら英語＋日本語表示の受入を追加する。

## 未確認事項・後続判断

- 本計画はソース読解のみ。実装・ビルド・テスト・実ブラウザ操作を実施したという記録ではない。
- 親はIssue本文の最新更新と前提PRマージ状態を実装開始時に再取得する。
- NOTEと通常履歴が共存した際の25%枠内優先順位、NOTE予約prefix衝突、本文末尾改行のcodec契約を回帰テストで明文化する。式は変更しない。
- 会話総数・import preview総数・期限・取り込んだNOTEのメモリ上限を設定へ分離する。20往復制限だけではメモリ総量を抑えられない。
- #8との表示回答更新・export時点の翻訳状態、#14の固定応答識別/実API未確認表示をマージ後に再確認する。翻訳#8は今回後回し。
