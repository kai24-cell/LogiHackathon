# Issue #7 検証記録

## 前提・範囲

2026-10-10、Issue #7本文・コメント（0件）、AGENTS.md、最新詳細設計4/5/6/10/12/17章と第21章を確認した。PR #15（Issue #6）がmainへマージ済み（49b2439）であることを確認し、最新mainからfeature/issue07-gemini-generationを作成した。既存ユーザー変更.gitignore・.vscode/settings.jsonは保持し、コミットしない。

今回はユーザーキー設定・選定済みコードの送信・英語構造化回答・失敗表示まで。#8の翻訳、#9の会話/TXT保存・取込、デモ認証、比較実験は追加しない。後続はこのPRのマージ後に最新mainから別PRで進める。

## 操作とAPI契約

- フォルダ走査→主選択→日本語質問・モード・入力トークン予算・モデルIDを入力。previewのマスク・除外理由と全文を確認する。
- Gemini APIキーをpassword欄で入力し「現在のモデルIDとキーを設定」。PUT /api/v1/settingsのJSON本文で渡し、キーを再表示しない。成功・失敗後にWeb入力を空にする。GET/DELETE settingsは設定済み状態・モデルID・設定版だけを返す。
- 「選定コードと質問をGeminiへ送信」は実API通信で費用の可能性がある。キー未設定でもpreviewは利用可能。生成は日本語原質問のまま英語JSONを指示する。
- POST /analysis/jobsはpreviewId・requestId(UUID)・settingsVersion・固定inputを受け202/jobIdを返す。GET /jobs/{id}は既存scanと生成の共通取得口。既存scanのjobId/state/errorCodeを維持し、追加フィールドはnull。
- 同一requestId/同一入力は同じjobへ結び付け、変更入力による再利用は409。セッション全体で生成1件、別要求は429。通信結果が不明なWeb再送には同じrequestIdを使用。失敗が確定したジョブの手動再実行は新requestId。
- 生成中も質問・選択を編集可能だが、実行開始時のinputをコピーして固定する。結果には実行時question/mode/workspaceId/snapshotIdを付け、編集中の質問と混同しない。
- POST /jobs/{id}/cancelでキャンセル。物理的な通信停止まで次の生成を受け付けず、課金取消は保証しない。自動再試行・AIによるJSON修復は0回。
- ジョブは最大100件・2時間のメモリ保持。満杯は429で無制限に増やさない。生成通信timeoutは設定可能な120秒（上限120）、全ジョブ240秒。バックエンド終了でキー・ジョブを破棄する。

## 予算・モデル・保存済みソース

保存previewのdigest/snapshot/5分期限/設定版・canExecuteを確認し、バックエンドで全文から安全余裕を再計数する。主選択を切断しない。送信対象として保存したJavaファイルだけをroot内・リンク拒否・サイズ制限付きでhash照合し、変化は409 FILE_CHANGED。照合のための読取だけで新コードをpromptへ混ぜず、除外・未走査ファイルは読み直さない。

Gemini models.getで実モデルのinputTokenLimit/outputTokenLimitとgenerateContent対応を確認し、安全余裕込みの入力と出力枠を超える場合は生成POST前に停止する。schema対応は実際のAPI拒否でMODEL_UNSUPPORTEDを表示し、別モデルへ黙って代替しない。固定の最新モデル名・実料金の既定値は採用しない。metadata取得もキーをURLへ入れずヘッダーで行う。概算は実tokensや費用の保証ではない。

公式確認日：2026-10-10。[generateContent REST](https://ai.google.dev/api/generate-content)、[models REST](https://ai.google.dev/api/models)、[構造化出力](https://ai.google.dev/gemini-api/docs/structured-output)。JDK21 HttpClient＋既存Jacksonを使い、新しいSDK依存は導入しない。固定HTTPS宛先、redirectなし、x-goog-api-keyヘッダー、temperature=.2、candidateCount=1、maxOutputTokensは既存予算設定、responseJsonSchemaを使用する。HTTP応答は累積1MiBを上限にする。

## 秘密情報の扱い

- .env/credentials等の固定走査除外を維持。application設定本文は保存していないため引き続き送信対象外。主選択の設定ファイルは不可理由を表示し、成功扱いで黙って削除しない。
- 保存Javaのpassword/apiKey/secret/token等の名前への文字列代入、Map値、@Valueの認証デフォルト値を[REDACTED]へ置換。行番号と宣言を保持し、preview表示と推定・送信は同じマスク済み材料を使う。
- PEM/JWT/認識可能なキー、認証text block、疑わしいコメントなど自動処理できないものは主選択なら422で停止、任意候補なら理由付き除外。原質問は改変しないため疑わしい認証情報は422で停止する。
- これは限定的なヒューリスティックで完全検出の保証ではない。任意名・エンコード・分割・独自形式の秘密、コードや質問自体の機密は利用者がpreviewを確認する必要がある。解析対象コードは編集しない。
- キーDTO/Credentials/PromptBundle/ReplyはtoStringへ生情報を出さない。外部エラー本文・cause・生リクエストをログや応答へ返さず固定コードに変換する。回答にキーが混入した場合は直接一致とJSON Unicode復元後の一致を除去する。
- キーは設定storeのみで保持し、実行中の通信には開始時の参照を使う。置換・解除でstore参照を破棄するが、実行中処理の参照は終了まで必要。完全なメモリ消去は保証しない。Webストレージ・Git・TXT・通常ファイルへ保存しない。

## 回答と後続Issue接続

プロンプトはresources/promptsへsystem・4モード・末尾指示・schemaを分離。概算材料はsystem/schema/contentsを一度ずつ連結し、APIではそれぞれの欄へ分けて送る。schemaは送信とバックエンド検証で同じファイルを使う。

必須フィールド・型・severity・未知フィールド・重複キー・JSON末尾の余分な値を厳密検証する。参照は送信前のref-N→fileId/相対パス/保持行範囲表に結び付け、不正IDは非表示と警告。JSON失敗はFAILED、マスク済みrawTextはメモリ内部だけに保持し、GETで公開しない。API usageの未提供値はnullで推定と分ける。thought/cacheをtotalへ勝手に加算しない。

#8はresult.answerEnglishとreferencesを入力に説明文字列だけ翻訳し、code/referenceIds/severityを保持すること。英語DTOを置換せず、日本語表示結果を別のフィールドへ付け、再翻訳はこのjobIdの保存済み英語結果を利用すること。#9は固定question/modeと英語DTOを成功時だけ会話へcommitし、翻訳結果をTXT表示へ使うこと。内部prompt・schema・キーは会話に含めない。履歴・ImportedContextの採用は共通materializerへ一度だけ追加して予算再計数し、第21章のTXT/コピペ契約を維持する。今回は初回質問・履歴0件。

## 検証・レビュー

- Java21・Maven Wrapper：`spotless:apply`、検証用コピーで`spotless:check verify`成功。101件（生成関連29件）、失敗・エラー・skipなし。検証コピーとJavaソース/テスト/プロンプトのhash一致を確認した。IDEのtarget更新を検証へ混ぜないためコピーを使い、既存依存キャッシュ・offlineで実行した。
- Web：`format`後、`format:check`・`lint`・`typecheck`・`test -- --run`（24件）・`build`成功。
- 拡張：同チェック・テスト2件・ビルド・VSIX生成成功。既存のrepository/LICENSE未設定警告は残る。
- 正常4モード、requestId再送・入力違い409・並行429・キャンセル・予算不足422・古い設定/保存ソース変更409、必須/未知/重複/余分JSON、参照除去、Unicodeエスケープ後もキーを回答に残さないことを検証した。
- 外部アダプターはローカルHTTP stubでヘッダー/固定JSON/schema、モデル上限でPOST前停止、timeout・auth・429・安全ブロック・出力上限・巨大応答、生成POSTの自動retryなしを確認した。ジョブの異常終了もstubで検証した。
- 初回検証で既存走査GET /jobs/{id}とのルート競合を検出し、共通取得口へ修正。Web表示テストの期待文言も実表示へ修正後に再実行した。
- 配布jarのHTTP smokeは8765番使用中のため、起動中アプリを停止せず`CHEAPREVIEW_SMOKE_PORT=18765`で実行し成功した。Web・走査・解析・検索・preview・予算拒否・設定状態/active状態を実HTTPで確認した。CIは既定8765を使う。

実キー・Gemini実通信、実ブラウザ/VS Code操作、大規模性能は未確認。Issue完了条件の実モデルによる英語回答の手動受入は、利用者がモデル/料金/キーを確認して実行する必要がある。実tokensとの推定誤差、モデルによるschema対応・安全判定はstubの成功で保証しない。

### 専用レビューと修正

専用TOMLを直接選択する機能はないため、`.codex/agents/reviewer.toml`を読ませたreviewerサブエージェントを使った。全体レビューと通信・秘密情報の独立レビューの2担当が、編集せずに確認した。全体担当のIssue再取得は権限確認待ちで止まったため中断し、親が取得済みの本文・コメント（0件）を共有した。担当自身によるテスト・実API・実画面の再実行はしていない。

確実なP2を1件修正した。不正JSONのAPIキーがUnicodeエスケープまたは混在表記だと、literal置換とschema検証後のマスクでは失敗rawに残った。`SecretRedaction`を通信アダプターとジョブの保持前へ共通適用し、schemaが不正でも解析不能でもキー表現を除去する。直接・全Unicode・混在の3表記×必須不足・途中切断の2種類で、設定解除後の内部failedRawが`[REDACTED]`だけになる回帰テストを追加した。大文字小文字の異なる別文字列は変更せず、Unicodeの16進数字だけ大小を許可する。

全体レビューでは、このP2以外の確実な追加不具合は確認されなかった。実ブラウザでの遅延応答・再送・キャンセル・再走査後のactive復帰は手動受入事項として残す。

修正後、2担当が共通除去・保持前の呼出し・6ケースの内部raw検証を再レビューし、P2解消と修正範囲に追加の確実な不具合がないことを確認した。親がJava101件と修正後jarの18765番smokeを再実行し成功した。レビュー中は対象コードを変更していない。
