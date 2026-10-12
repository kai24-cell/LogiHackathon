# Gemini 3.8 Flash料金設定（2026-10-12）

公式: https://ai.google.dev/gemini-api/docs/pricing 。通常API Standardの100万tokensあたり入力0.75 USD、出力3.75 USDをapplication.propertiesへ適用。出力単価はthinkingを含む。確認日2026-10-12、導入価格は2026-12-31まで。自動更新はせず、期限後は公式料金と設定の再確認が必要。

モデルID内の`.`を保持するため、設定キーは`cheapreview.budget.prices[gemini-3.8-flash]`を使う。未知モデルの料金はnullのまま、無料として扱わない。モデルcontext上限は今回変更しない。

## 数式と制限

概算入力費用=ceil6(推定入力tokens×0.75/1,000,000)、概算最大出力費用=ceil6(2048×3.75/1,000,000)。各項を小数6桁へ切り上げてから合計。安全余裕はtokens予算に用い、費用へ二重加算しない。

入力1041なら入力0.000781、最大出力0.007680、総額0.008461 USD。現在の概算にthinking量の独立推定、cacheと翻訳の料金は含めない。既存の警告表示を維持し、実料金を保証しない。

ユーザー確認済みusage（入力613、回答973、thinking578、total2164）では、provider合計2164=613+973+578。合計2164へthinkingを再加算しない。実usageから通常単価の参考額を計算するなら、入力613×0.75/100万＋(回答973+thinking578)×3.75/100万=0.006276 USD（丸め前）。この実費計算画面は追加せず、実請求額とは区別する。

## 検証

設定ファイルからのモデルIDと単価のbind、概算内訳の十進丸め、preview APIでの金額DTOとthinking警告、Webでの内訳表示、HTTP stubの実usage分離を回帰検証する。新しい実Gemini/Cloud Translation通信、認証ファイル読取、料金編集UI追加、ZIP作成は行わない。

### 実行結果

- Java: Maven Wrapper offline `spotless:apply verify`成功。192件、失敗0/エラー0。設定bind・preview API・実usage分離の追加3件を含む。
- Web: Prettier、format check、Lint、型チェック、42件のVitest、build成功。実BudgetSummaryのUSDと入力/出力/合計の表示を検証。
- 読み取り専用レビュー: 追加の確実な不具合なし。レビュー担当はコード編集・外部通信・テスト再実行を行っていない。
- 実ブラウザで再起動後の料金表示は利用者確認待ち。実Gemini/Translation追加通信0、ZIP作成なし。
