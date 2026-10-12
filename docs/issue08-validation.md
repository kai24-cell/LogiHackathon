# Issue #8 翻訳の実装・検証記録

確認日：2026-10-12。Issue #8本文・コメント（コメントなし）、AGENTS.md、最新詳細設計version02の13章・21章を照合した。
基点は#14 `0b66ff6`。実APIの追加通信は行わず、ローカルHTTP stubと固定providerのみを使用した。`.env`・実認証JSONを読取・変更していない。

## 実装と契約

- 英語生成の予算・schema・参照検証を維持し、Result.answerEnglishは不変。表示訳はJob.translation.displayAnswerとして別に保持する。
- 翻訳対象はsummary、sections.title/body、findings.title/explanation/suggestion、limitations。code/referenceIds/severityはAPIへ送らず元DTOからコピーする。
- 今回送信した保存済みcodeチャンクだけからJavaParserのsymbolを抽出し、完全Javaではない末尾の省略メソッド署名はコメント・文字列を除いた字句走査で補う。翻訳のためにディスク・走査対象外ファイルを読み直さない。
- symbol・相対パス・バッククォート区間を長い順・識別子境界付きでplaceholderへ置換する。原文のplaceholder形式も退避し、欠落・重複・未知・変更はその文字列全体を英語へ戻す。
- 初回TRANSLATING後、完全翻訳はSUCCEEDED、部分成功／全失敗はTRANSLATION_FAILED。英語回答とusageは全状態で保持する。未翻訳fieldを日本語警告で示す。
- POST `/api/v1/jobs/{id}/translate`は202を返し、既存回答と同じ保護辞書だけを再利用する。Gemini・走査・予算選択は再実行しない。完了済みstateを巻き戻さずtranslation.status=RUNNINGとattemptで表す。
- GET `/api/v1/translation/status`は設定の有無だけを返す。認証値・パス・projectをレスポンスへ含めず、有効性は通信まで未確認とする。
- #9への表示更新イベントTranslationDisplayUpdatedを設けた。英語履歴は翻訳結果へ置換しない。会話commit／TXTとの同居検証は統合担当で行う。

## 認証・公式確認

明示環境`GOOGLE_APPLICATION_CREDENTIALS`と`CHEAPREVIEW_GCP_PROJECT`をSpringのみ参照する。`.env`予約項目はLocalDemoConfigから翻訳へ渡しておらず、起動PowerShellの環境に設定が必要。
起動・status・未設定fallbackで認証ファイルを開かず、ADC／metadata探索を行わない。資格情報は実翻訳時だけServiceAccountCredentialsで読み、OAuthの自動retryを無効化する。
今回は安全な認証型選択のためservice-account JSONに限定した。Java 21のビルドに安定版google-auth-library-oauth2-http 1.43.0を固定。

公式確認日2026-10-12：

- [translateText REST契約](https://docs.cloud.google.com/translate/docs/reference/rest/v3/projects/translateText)：v3 global、en→ja、text/plain、配列index対応。
- [認証](https://docs.cloud.google.com/translate/docs/authentication)・[IAM](https://docs.cloud.google.com/translate/docs/access-control)：API有効化・課金・roles/cloudtranslate.userが必要。
- [Google auth安定release](https://github.com/googleapis/google-auth-library-java/releases/tag/v1.43.0)：固定版は最新値と断定しない。

最大10,000 code pointsとcontents1,024件は設計上の暫定値。文・段落単位で分割し、単純文字切りをしない。翻訳試行の全batch・認証・限定retryで共通30秒期限、429/503のみ1秒後最大1回retry。それ以外は再送なし。生成期限240秒は英語検証完了時に解除し、翻訳失敗で英語結果を失わない。翻訳のみキャンセル可能で、英語成功をCANCELLED生成に変更しない。
OAuthの物理的な通信即時停止は保証しないが、割込みを無視する認証stubでも呼出側・後続要求が期限内に戻ることを検証する。
実翻訳料金・無料枠・請求は未確認。Webへ別課金の可能性を表示し、生成概算へ二重計上・無料扱いしない。

## 自動検証

以下はstubのみの結果。実API・実ブラウザの成功とは区別する。

| コマンド | 結果 |
| --- | --- |
| backend `mvnw.cmd -o ... spotless:apply verify` | レビュー修正後Java 158テスト成功、Spotless check成功、jarビルド成功 |
| backend `mvnw.cmd -o ... -Dtest=GenerationIntegrationTest spotless:apply verify` | 最後の再翻訳中止警告保持を含む関連27テスト成功、jar再ビルド成功 |
| web `npm run format:check` / `lint` / `typecheck` | 全て成功 |
| web `npm run test -- --run` / `build` | Vitest 33成功、production build成功 |
| `verify_api.ps1 -Scenario normal/invalid/timeout` | 専用サンプルの予算/schema/参照・異常schema・timeout全て成功（固定provider、外部通信0） |
| `python -m unittest discover -s scripts/tests -p "test_*.py"` | 診断Reader 2成功 |
| `git diff --check` | 成功 |

JavaのTranslationServiceTest 7件、CloudTranslationClientTest 6件、TranslationSymbolsTest 2件、GenerationIntegrationTestの再翻訳・中止回帰を含む。テストは以下を確認した。

- 説明だけの翻訳、code/refs/severity不変、原文保持。
- placeholder欠落・重複・変更・未知ID、同名の一部一致、原文ID衝突、一部fallback。
- Unicode code pointsとbatch上限、安全に分けられない長文の英語保持。
- v3 URL／en→ja／text/plain／Bearerヘッダー／レスポンスindex、429/503のみ1retry、timeout無再送。
- 認証未設定・無効projectの有無判定で認証JSONやmetadataを読み込まない。
- 再翻訳してもGeminiは最初の1回だけ、英語結果・usage・snapshotが同一。
- Webの日本語／英語表示、未翻訳field警告、HTMLとして回答を解釈しないこと。

## 未確認・後続

実Cloud Translationの認証・API疎通・訳品質・課金・quotaは未確認。#14の実ブラウザ／VS Code接続受入は保留で、その結果を今回完成扱いにしない。
実ブラウザの生成→訳表示→再翻訳操作、#9会話/TXTと#10配布ZIPの合流は後続手動・統合確認が必要。
マージ順はPR #17（#14）→#8→#9→#10。#14の未確認事項は#8のstub成功では解消されない。マージ・実API追加通信は行っていない。

## 専用レビューと対応

`agent_type=reviewer`を起動し、このworktreeの`.codex/agents/reviewer.toml`全文を読ませた専用指示でレビューした。レビュー中は対象コードを編集せず、担当は実API・秘密ファイル読取・投稿を行っていない。

1. P1 省略メソッド署名のAST外symbol欠落：送信chunkだけの字句補完を追加し、署名専用のメソッド名・引数名が翻訳送信されず復元される回帰を追加。
2. P2 翻訳中止非対応・各batch timeoutで長時間active：全試行共通30秒と翻訳のみ中止を追加。初回／再翻訳中止後の英文・usage保持、active解放、遅延結果不採用を回帰で確認。
3. P2 OAuth SDK割込み無視の懸念：認証も共通期限で待機し、割込み無視stubと後続要求が期限内に戻る回帰を追加。物理的な即時停止・課金取消は保証せず未確認と記録。
4. P2 本文timeout・容量超過が一般失敗へ潰れる懸念：定型TRANSLATION_TIMEOUT／TRANSLATION_INVALID_RESPONSEだけを伝搬し、ヘッダー後本文停止とサイズ超過をstub回帰へ追加。

固定providerでは親環境にGCP認証が存在しても翻訳を無効化するfactory回帰も追加した。元Geminiキーは再翻訳Entryへ保持せず、保存済み英語Answerと保護辞書だけを使う。
専用reviewerの修正後再レビューでは、4件の対応と回帰テストを直接確認し、追加の必須修正なし。担当はテストを再実行せず、親の実行結果と独立レビューを区別している。reviewer自身の`git diff --check`は成功。
レビュー修正後の最新jarでpackagedstub3ケースとPythonReader2も再確認し全成功した。

P3後続改善：字句補完はJavaのUnicodeエスケープ前処理をしない。クラス外の省略署名で識別子がUnicodeエスケープ表記の場合、正しい名前を保護辞書へ追加できない可能性がある。通常識別子は回帰で確認済みだが、この特殊ケースを識別子保持の完全保証としない。
