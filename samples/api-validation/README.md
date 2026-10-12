# API動作確認専用サンプル

`case.json` に固定の日本語質問、主選択、期待する関連ファイル、入力トークン予算を記録しています。
OrderServiceが数量を検証し、OrderRepositoryへ保存を委譲するため、この2ファイルを期待対象としています。
サンプルのJavaコード自体を実行せず、通常の走査・解析・検索・予算・生成・回答検証を通します。

固定応答は `backend/src/main/resources/demo/answer-en.json` です。どのプロジェクトにも通用する解析結果ではなく、画面と検証経路の確認用と明示しています。
`answer-ja.json` は#8の翻訳保持テストに使う期待値であり、今回の翻訳実績ではありません。

ビルド後、`scripts/verify_api.ps1` でキー不要の正常ケースを確認できます。
`-Scenario invalid` は回答schema不正、`-Scenario timeout` はタイムアウトを固定再現します。
実APIは `.env` をローカルで設定してから `scripts/verify_api.ps1 -Real -AllowPaidApi` で明示実行します。
1回のコマンドで生成ジョブを1回だけ開始し、自動再試行はしません。モデル情報取得は別のGETです。
費用は利用モデルとAPI側の設定に依存します。未知料金を無料とは扱いません。
