# Issue #9 会話履歴・TXT引継ぎの検証

確認日：2026-10-12。Issue #9本文（コメント0件）、AGENTS.md、最新詳細設計version02第10・11・17・21章を照合した。単独実装元は#14の`0b66ff6`、翻訳#8との統合結果は末尾に記載する。マージ順はPR #17→#20→#9実装PR→#19。以前の計画PR #18を実装PRが代替する。

## できる操作

1. 正規の拡張接続後にフォルダを走査し、主選択・質問・モード・入力予算を指定する。
2. 同じworkspaceでは会話を保持する。英語生成成功だけを1往復として確定し、次の概算へ英語構造化回答と質問を利用する。英語生成失敗・生成cancel・削除済み会話への遅延回答は確定しない。
3. 「会話をTXT保存」で確定済みの質問と表示回答を保存する。生成中でも既に確定した往復だけを保存できる。
4. 「会話を引き継ぐ」からUTF-8 TXTを選ぶか本文を貼り付け、「引継ぎをプレビュー」で件数・文字数・見込みtokensを確認する。
5. 「この履歴で開始」の確認後に新しい会話へ切り替える。元の会話を黙って上書きしない。「新しい会話」は確認後に旧会話を破棄する。

## 履歴と予算

通常会話はメモリで最大20往復、生成への採用は最新から最大6往復。古い往復から省略し、採用・省略数を概算欄に表示する。英語JSONのsummary・sections・findings・limitationsを再利用し、日本語表示や前回添付コード全文を通常履歴として重ねて送らない。モードは各往復に保持する。

履歴を含む材料は既存のUnicode推定と安全余裕で再計算する。`historyTokens = T(主選択＋履歴) − T(主選択)`、`relatedAdditionalTokens = T(最終prompt) − T(主選択＋履歴)`。それぞれの見出し・丸めも完全promptで一度だけ数えるため、必須分＋履歴＋関連追加分が総tokensと一致する。履歴の寄与は入力予算の25%以内、かつ完全promptの安全余裕込み最低予算以内。関連コードは履歴を確定した後に追加する。

最新1往復が入らない場合は`HISTORY_TOO_LARGE`として予算増加・新会話を案内し、勝手に削除しない。NOTEは全文を保持し、途中切断しない。NOTEと往復が共存するとNOTE全体を先に確保し、その残り枠へ最新往復を入れる。会話versionをpreviewと固定入力へ含め、会話変更・clear・import・snapshot変更で旧概算を無効化する。

前回参照は現在のsnapshotの相対パスへ解決できるものだけ探索の起点へ加え、mandatoryにはしない。消失した参照は警告し、旧コードや走査対象外を読み直さない。今回の主選択と保存済みsnapshotを回答根拠とする。

## TXT・秘密情報

TXTはUTF-8 BOM付き・LF、Asia/Tokyoの保存ファイル名、`CHEAPREVIEW_CHAT_V1`と`EXPORTED_AT`、USER/ASSISTANT単独行で構成する。本文の区切り行・先頭backslashを一段escapeして復元する。末尾改行・空行・日本語・emoji・コードブロックを保持する。先頭NOTEだけは独立USER記録を許可し、通常の完了往復がNOTEの予約prefixで始まっても誤認しない。NOTE＋通常往復の再保存・再取込も双方を保持する。

専用形式を名乗る壊れたTXTをNOTEへ降格しない。任意文章はNOTEとして扱い、USER/ASSISTANTらしい記述から役割を推測しない。取込資料はuserの参考コンテキストへ明示区切りで添え、system指示・実際のassistant role・英語JSONへ復元しない。旧snapshotの完全復元機能ではない。

ファイル最大1MiB、本文100,000 code points、20往復。Webはbyte上限とfatal UTF-8 decode、サーバーは不正surrogate・本文容量・順序を検証する。取込JSON本文はJackson読込前に2MiBで制限し、Content-Lengthだけを信用しない。JSON escapeで1MiBファイルが2MiBを超える場合は413になるため、上限内ファイルの送信成功を保証しない。失敗時はWebの原文を保持する。import previewは5分、利用は1回だけ。

TXTは質問・表示回答・回答内コード片・相対参照だけから作る。設定・APIキー・認証・system・schema・自動添付コード全文を参照しない。既知の現在キーと既存の秘密情報パターンに一致したら拒否する。ユーザーが質問に書いた絶対フォルダパスも保存前に拒否し、黙って改変しない。未知の秘密を完全自動検出できる保証はないので画面で注意を示す。会話/import DTOのtoStringは本文を伏せる。

## 暫定設定と設計上の調整

- `cheapreview.conversation.max-conversations=128`、`max-imports=128`。通常の手動利用には十分な暫定上限として無制限保持を防ぐ。設定例から変更できる。
- 会話は最後の操作から2時間で破棄。既存のjob結果2時間保持と合わせた暫定値。取込previewは設計どおり5分。
- 秘密・絶対パスを含む本文は原文保存要件より漏えい禁止を優先して保存を拒否する。利用者の原文を無断でマスクした成功扱いにはしない。
- #8との統合では初回翻訳成功・部分失敗・全失敗でも英語生成成功を1往復commitし、翻訳表示を同じ往復へ更新する。再翻訳で往復を増やさず英語履歴を変更しない。単独#9では英語＋未翻訳注記を表示・保存する。

## 実行結果

- Maven Wrapper（Java 21、既存ローカル依存、offline）：`spotless:apply verify`でJava **164テスト**、失敗0・エラー0。統合用の遅延表示更新ガードを含め最終verify内Spotless checkとjarビルド成功。
- Web：`npm ci --offline`、`npm run format`、`format:check`、`lint`、`typecheck`、`test -- --run`、`build`成功。**38テスト**成功。
- 追加回帰：TXT往復/参照行7ケース、会話・予算境界・上限・clear・表示更新・期限・URL/絶対パス・遅延event10ケース、取込HTTP byte上限/strictUTF-8/本文維持3ケース、通常生成→追加質問→TXT→import→再preview・clear競合・cancel/不正回答未commit・取込参照不一致4ケース。
- 既存の秘密除外・Java解析・検索・予算・生成・診断テストも維持。stub/mockだけを使い、`.env`・認証JSONの読み取りと実API通信は0回。
- sandbox内Java/esbuildのアクセス制限は権限付き同コマンドで解消。初回verify中の追記に伴うSpotless違反は整形して最終verify成功を確認した。

## 未確認事項

実ブラウザのファイル選択・Windowsの保存ダイアログ・VS Code再接続・再起動後の手動TXT引継ぎは未実施。実Gemini/Translation・実請求額は未確認。翻訳#8との統合検証・CIの状況は末尾に記載する。秘密情報の完全検出を保証しない。大きい確定会話TXTは取込容量を超える場合があり、本文を利用者が明示的に削減する必要がある。

## 専用レビュー指摘と対応（PRコメント案）

`reviewer`ロールを起動し、`.codex/agents/reviewer.toml`の専用定義を読ませた。レビュー中は対象コードを変更せず、担当は編集・投稿・実APIを行わなかった。3件のP2を既存コードと照合して妥当と判断した。

| 指摘 | 修正前 → 修正後 | 回帰検証 |
| --- | --- | --- |
| 通常HTTPS URLをドライブ絶対パスと誤判定 | `https://example.com/docs`の`s:/`で保存拒否 → ドライブ指定の直前条件を判定しURLは保存可、`C:\project`/`C:/project`/UNCは引き続き拒否 | URL・Windowsパス3形式のstoreテスト |
| 2時間期限切れから新会話へ復帰不可 | DELETE 404で作成処理も止まる → `CONVERSATION_NOT_FOUND`の404だけ破棄済みとして新規作成。他の認証・通信障害は止める | 固定Clockによる期限検証、Webの404復帰/401停止 |
| 取込TXTのコード参照を新snapshotと照合しない | 通常会話の前回参照だけを検査 → 自アプリ出力の`ref-N: 相対path start–end行`を認識し現在のsnapshot集合と照合、不一致を参考情報として警告 | `../not-scanned.java`を含む取込で警告、採用コードは現在のService.javaだけ、provider呼出0 |

任意文章のパスを推測してディスクへアクセスすることはない。未知の参照書式は資料として保持し、自アプリ書式の照合済みと誤認しない。#8統合時の翻訳成功/部分失敗/全失敗の1回commitと再翻訳表示更新は、再レビュー後も統合テストが必要な確認事項として残す。

再レビューは初回3件の修正妥当性を確認し、新しい確実な不具合はなかった。統合懸念として未commit・取消・破棄済みjobへの遅延翻訳eventが会話版を進める点が挙がったため、確定往復が見つからなければ表示・versionを変更しないガードと回帰テストを追加した。レビュー担当はテスト再実行や実ブラウザ確認を行っていない。

ガード追加後の最終レビューでも、初回3件と遅延event懸念の解消を確認し、追加指摘はなかった。#8の翻訳のみ中止は英語生成成功を取り消す操作ではなく、統合時は取得済みの英語往復を保つ。実際の翻訳更新・中止・再翻訳との統合テストは以下に記録する。

## #8との統合検証

`feature/issue09-conversation-integrated`は翻訳#8の`e137d62`（PR #20）を祖先にし、元#9の`687da4b`を取り込んだ独立ブランチ。元8/9ブランチは保持した。PRは#8をbaseとするstacked形式で、マージ順はPR #17→#20→#9実装PR→#19。前提マージ後はmainへretargetする。

英語schema・参照検証後、`GENERATING`を確認した同期区間で会話を1回commitし、直後に英語Resultを保持して`TRANSLATING`へ進む。生成取消はここへ到達せず未commit。翻訳のみ中止・翻訳部分失敗・全失敗では取得済み英語を失わず、再翻訳は同じ往復の表示だけを変更する。

内部`TranslationDisplayUpdated`のbooleanを`hasUntranslated`へ明確化した。HTTPの`partialTranslation`は変更していない。内部eventは部分失敗または全失敗でtrue、翻訳中止でもtrueにし、全失敗のHTTP `partialTranslation=false`でもTXTに未翻訳注記を残す。listenerはjobIdに一致する既存turnを探してConversationServiceへ一方向に表示を届け、GenerationServiceのロックを取り直さない。未知job・clear・期限切れへの遅延eventは表示もversionも変更しない。

Webは英語Resultが確定した翻訳中にも会話を取得し、既存往復をTXT保存できる。翻訳完了・失敗・中止・再翻訳表示変更時にも取得し、会話versionで古いpreviewを無効化する。同じポーリング状態では重複通知しない。

- Java **188件**すべて成功、Spotless check・verify・jarビルド成功。
- Web **40件**すべて成功、format/check・Lint・型チェック・build成功。
- 統合回帰：日本語stub成功・部分失敗・全失敗で英語1往復確定、表示TXTと未翻訳注記、usage/参照保持。再翻訳でTXT/version更新・旧preview拒否・往復数/英語履歴不変。翻訳のみ中止で英語とusage保持。生成cancel未commit。期限切れ/clearへの翻訳event無視。
- 再翻訳テストのmock切替が以前のfailure stubを呼ぶ補助不具合を`doAnswer`へ修正し、全verify成功を確認した。

stub3ケース（normal/invalid/timeout）は全件成功。normalは翻訳認証未設定の英語fallback（`TRANSLATION_NOT_READY`）、他2件は既定の失敗コードを確認した。固定provider選択では環境にGCP認証があってもCloud Translationを無効化し、実認証を読まない。Python診断2テストも成功。

統合専用reviewerは`.codex/agents/reviewer.toml`に従って差分・関連コード・設計・追加テストをreadonly確認し、修正が必要な確実な不具合はなかった。英語1回commit、翻訳失敗/中止注記、再翻訳表示更新、version失効、一方向event、未知/期限切れ/clear通知無視、翻訳中のTXT取得を確認した。レビュー中の対象コード編集はしていない。

最新CI結果はPRへ記録する。実API・認証ファイル読取・実ブラウザ操作は行っていない。
