# Windows導入・提出ガイド（Issue #10の先行整備）

このガイドはmainのIssue #7までの実装を対象にしています。走査、Java解析、関連検索、入力予算によるコード組み立て、Gemini英語回答が利用できます。日本語翻訳（#8）、会話/TXT（#9）、完成版デモ・比較実験は未実装です。Issue #14のPR #17は未マージの前提PRで、固定応答・ローカルデモ認証はこのブランチの機能として説明しません。

## 1. 前提ソフトと展開

Windows 10/11、Java 21、ローカルVS Code 1.95以上を用意します。JREとVS CodeはZIPに同梱しません。ZIPを通常のローカルフォルダへ展開し、そのフォルダでPowerShellを開きます。WSL/SSH/コンテナの拡張ホストは対象外です。

```powershell
java --version
```

`21`を確認します。ZIPにビルド済み`dist/cheapreview-0.1.0.jar`と`dist/cheapreview-0.1.0.vsix`が揃えば、実行時のNode.js/npm/Maven/ネットワークによる依存取得は不要です。Gemini生成時はインターネット接続が必要です。

ソースからビルド・梱包する場合はJDK 21（javacも21）、Node.js 22.12以上、npm、Git、Python 3.10以上が必要です。Pythonは梱包検査用で、通常のアプリ起動には不要です。

## 2. VSIXをインストール

VS Codeの拡張画面を開き、「…」→「VSIXからのインストール」→`dist/cheapreview-0.1.0.vsix`を選びます。必要ならウィンドウを再読み込みします。コマンドパレットに`CheapReview: Open Web`が現れることを確認してください。CLIがPATHにある場合は`code --install-extension .\dist\cheapreview-0.1.0.vsix`でも導入できます。

## 3. 起動・接続

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

実行ポリシー指定はこのプロセスだけです。起動用PowerShellは開いたままにします。ブラウザが開いたら、VS Codeでコマンドパレット→`CheapReview: Open Web`を実行し、Webの「VS Code接続済み」を確認します。表示反映には最大10秒程度かかります。

Springは`127.0.0.1:8765`で待ち受けます。他のPCへ公開しません。URLの接続fragmentや`%LOCALAPPDATA%/CheapReview/runtime.json`の内容は共有しないでください。ページ再読み込みで接続が消えたらOpen Webを再実行します。

## 4. キー不要でサンプルを走査・検索・概算

1. Webの「フォルダ選択」からVS Codeの選択ダイアログを開き、展開先の`samples/code-search`を選びます。サンプル自身を起動する必要はありません。
2. ファイル一覧で`src/main/java/demo/OrderController.java`を主選択します。
3. 観点を`SERVICE_REVIEW`とし、「注文を保存する処理を確認したい」と入力して検索します。`OrderService.java`と`OrderRepository.java`の候補・選定理由を確認します。
4. 送信コードの組み立てで「入力トークン予算」を設定します。既定は8,192 tokens、入力範囲は1,024〜100,000です。推定入力と安全余裕、最低予算、採用・除外理由、コードの省略行を確認します。
5. 最低予算を下回ると実行不可になり、主選択は勝手に消えません。予算を増やすか選択を変更します。モデルの料金が未設定なら「料金不明」で、無料という意味ではありません。

固定質問と関連ファイルの理由は`samples/code-search/expected-searches.json`、解析だけのサンプルは`samples/java-analysis`、最小走査は`samples/scan-demo`を参照してください。検索は日本語原文のままで、英語識別子との語彙不一致には限界があります。

## 5. Gemini英語回答（任意・有料の可能性）

このmainではモデルIDとGemini APIキーをWebで入力します。モデルIDは翻訳APIではなくGeminiの生成モデルです。公式情報で利用可能なIDと料金を確認してください。「設定済み」は入力受理を示し、実API疎通成功の保証ではありません。入力欄が消えただけで成功と判断しないでください。

送信前にpreviewの全文・参照・マスキングを確認し、明示的なGemini送信ボタンを押します。APIキーはSpringのメモリ保持で、Webへの再返却・保存は行いません。画面は英語構造化回答、送信時の質問、根拠の相対パスと行範囲、推定値とAPI usageを表示します。翻訳は未実装です。失敗時はHTTPステータスと固定コードを確認し、自動再送はしません。

デモ認証をローカル設定から有効化する経路と固定回答は#14 PR #17の対象です。これらを利用するには同PRのマージ後の手順を確認してください。固定回答の結果やtokensを実APIの実績として扱わないでください。

## 6. 停止・再起動・よくある状態

起動用PowerShellでCtrl+Cを押して終了します。起動スクリプトは自分が開始したJavaプロセスと一時runtimeを片付けます。再起動後はOpen Web→フォルダ選択→再走査が必要です。設定キーやジョブ結果は再起動をまたいで保持しません。強制終了後は次回起動時にruntimeを置換します。

| 状態 | 確認する操作 |
| --- | --- |
| Javaの版違い | `java --version`、JAVA_HOME/PATHをJava 21へ設定 |
| ポート8765使用中 | 以前の起動用ターミナルを確認して停止。無関係なプロセスを一括終了しない |
| VS Code未接続 | ローカルVS CodeでVSIX導入・Open Webを確認 |
| `401 INVALID_CONNECTION` | Open Webで再接続 |
| `403 INVALID_ORIGIN` | 127.0.0.1のURLを使用。Vite開発時は`start.ps1 -Dev` |
| ファイル変更・古い概算 | 再走査、検索・preview再取得 |
| 予算不足 | 最低予算以上へ変更、または主選択の見直し |

翻訳認証は#8実装後にバックエンドの`GOOGLE_APPLICATION_CREDENTIALS`等で別途設定する予定です。認証JSONをZIP・Gitに置かず、内容をチャットに貼らないでください。現時点では翻訳認証設定や疎通の成功を案内しません。

## 7. 提出ZIPの作成者向け

変更をレビュー・コミットして、クリーンな作業ツリーで次を実行します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package.ps1
```

package.ps1は既存build.ps1を毎回呼び、Web/Java/拡張の検証とビルド後にZIPを作ります。古い成果物を最新扱いしません。ビルドでは生成専用のstatic/拡張distを初期化し、Maven clean verifyで削除済みクラスの残存も防ぎます。リンクを含む生成フォルダの初期化は拒否します。npm ciとMavenの依存取得にはネットワークや数分以上の時間が必要な場合があります。別途buildを繰り返してからpackageする必要はありません。

ZIPは`dist/cheapreview-source.zip`です。trackedソースから許可したアプリソース・scripts・Markdown設計/導入文書・samples・CI設定と、今回ビルドしたjar/VSIXを収録します。Word原本とエディタ個別設定は収録せず、本文・表を保持したMarkdown設計を同梱します。ルートの`distribution-manifest.json`にsource commitと収録ファイル名を記録します。

実値の.env、認証JSON、runtime.json、利用者コード、node_modules、依存キャッシュ、ログ、秘密鍵を同梱しません。trackedファイルも検査し、危険パス、認証JSON構造、代表的な秘密キー形式、値入り.env.exampleがあれば失敗させます。jar/VSIX内部も検査します。空欄.env.exampleだけは許可します。任意形式の秘密を完全に検出する保証はないため、提出者の内容レビューも必須です。

提出前に別フォルダへZIPを展開し、上記2〜6を手動確認してください。未実施なら成功と記録しません。未実装の日本語回答・追加会話・TXT保存・実験結果は梱包完了条件に含めず、Issue #10全体は未完了のままです。

## 8. 後続PRの合流順と受入準備（2026-10-12）

マージ順は **PR #17（#14）→ #8翻訳PR → #9会話/TXT PR → PR #19（#10）** です。PR #19は従来のmain（#7まで）を基にした先行整備のため、この順番だけで自動的に全機能が揃ったと判断しません。前提PRのマージ後、最新mainとの差分、README、起動スクリプト、CI、実ZIPを再確認します。現在のPR #19単独では翻訳・会話・デモ認証を利用できません。

翻訳認証の準備では、認証JSONをプロジェクト外に保管し、バックエンドを起動するPowerShellプロセスの`GOOGLE_APPLICATION_CREDENTIALS`にそのパス、`CHEAPREVIEW_GCP_PROJECT`にGCP project IDを設定します。Cloud Translation APIの有効化・課金・翻訳実行権限を用意します。内容を表示せず、Git・ZIPへ認証JSONを追加しません。認証設定の存在は実疎通成功の保証ではありません。#8合流後に「日本語表示／英語原文」と認証未設定表示を確認し、失敗時の英語表示・再翻訳（Geminiを再生成しない）を確認します。認証未設定のstub検証と実API確認は区別してください。

#9合流後は、確定済み会話のTXTダウンロード、ファイル取込、貼り付けプレビュー、新会話で開始を確認します。再起動後は再接続・フォルダ選択・再走査のあとに履歴を取り込みます。TXTは旧snapshotの復元ではなく参考資料です。保存する利用者の会話やAPI確認の回答ファイルは配布サンプルではありません。

配布にはtrackedの公開サンプル・固定回答fixture・schema・Markdown検証記録を収録できます。`.cache`内の実回答、`.playwright-mcp`、`validation-results`、`user-workspaces`、ログ、利用者TXT、認証実値は収録しません。許可リスト内へ利用者コードや実回答を移動してtrackedにしないでください。実APIの回答は名前だけで完全検出できないため、梱包前のtracked内容レビューも必要です。

- [ ] 前提PR合流後のクリーンビルド・stub検証・ZIP梱包検査
- [ ] ZIP展開先のWindowsで正規接続、デモ設定、サンプル概算
- [ ] 翻訳未設定とstub表示を区別し、認証済み実翻訳は別途明示許可で確認
- [ ] 確定会話をTXT保存し、再起動・再接続・再走査後の取込と貼り付けを確認
- [ ] 実API受入が未完了なら、提出ガイドにも未確認として残す
