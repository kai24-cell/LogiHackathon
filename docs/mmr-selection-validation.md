# MMRによる任意送信候補の選択

## 変更範囲と設定

2026-10-13のユーザー指示により、最新main `2ab17959f01e602d8e1e273b655867313deacc9e` を基に、任意候補の固定density順をMMRへ変更する。詳細設計version02の9.4・10章にある「MMRを追加しない」は今回の範囲に限り変更する。検索関連度、TF-IDF、トークン推定、主選択の保持、呼び出し補完、マスキング、完全promptの安全余裕判定は維持する。

`cheapreview.budget.mmr.lambda=0.7` が既定値。設定例は `backend/config/application.example.properties`。Springの外部propertiesまたは環境変数 `CHEAPREVIEW_BUDGET_MMR_LAMBDA` で変更できる。有限の0〜1だけを受け付け、不正値は起動時に拒否する。UIは追加しない。設定は起動時に固定し、変更後は再起動・再previewが必要。

## 数式と選択順

関連度は従来どおり `R_i = 0.50 C_i + 0.30 D_i + 0.20 F_i`。各項は0〜1。

選択済み集合Sには主選択・必須分を最初から含める。任意候補iについて以下を計算する。

```
redundancy_i = max cosine(i,j), j ∈ S （Sが空なら0）
MMR_i = λ R_i − (1−λ) redundancy_i
ΔT_i = estimate(完全prompt(S + i)) − estimate(完全prompt(S))
priority_i = max(0, MMR_i) / max(ΔT_i, 1)
```

これは標準MMRにトークン費用を加えたCheapReview独自の暫定ヒューリスティック。λ=0.7は関連度寄りの初期値であり、最適性や精度向上を保証しない。比較実験で今後調整する。

候補を追加した完全promptの推定値Tと `max(ceil(0.20 T),512)` の安全余裕が入力予算に収まるものだけを扱う。入らない候補は飛ばして後続を試す。本文・参照・指示・schema・質問・履歴を同じ推定器で計数し、今回その係数や丸め方は変更しない。

優先度降順、MMR降順、関連度降順、相対パス昇順、fileId昇順で決定する。採用ごとにSと完全promptを更新し、残候補のスコアを再計算する。負MMRも優先度0として予算内なら採用可能であり、冗長な候補を必ず排除する閾値は設けない。増分0以下は分母1とし、完全promptの予算検証は省略しない。関連度0は従来どおり任意候補から除外する。

採用理由にはλ、R、最大cosine、MMR、増分tokens、優先度、選択式版 `mmr-incremental-tokens-v1` と集約版を記録する。理由はpromptに含まれず、予算を変えない。

## ファイルチャンクとメソッドベクトルの対応

集約版は `retained-method-centroid-l2-v1`。実際の送信チャンクのmethodIdsに含まれるメソッド（保持されたconstructorも含む）の既存L2正規化TF-IDFベクトルを平均し、再度L2正規化する。省略した本文のメソッドを混ぜない。メソッドなし・構文解析失敗では既存のmethodId=nullのファイル索引を使う。保持ベクトルが空ならcosineは0。

語彙とidfは同じsnapshotの既存索引で固定する。検索のRは従来のファイル内最大メソッドcosineを使い、MMRの冗長性にだけ集約cosineを使う。ディスクを再読込せず保存済み解析・ソースだけを使う。

索引は識別子・型・役割・コメント等によるもので、本文全体の意味ベクトルではない。平均によって短い重要メソッドの語が薄まり、クラス共通情報に重複度が影響される。日本語と英語の語彙が一致しない限界は変わらない。全候補間行列は保存せず、候補ごとの最大冗長度を新規採用分だけで更新する。類似度呼出しは最大O(N²)に抑えるが、候補ごとに完全promptを再計数するため大量候補での性能は別途確認が必要。

## ローカル専用比較の保護

変更前の実装9ファイルを `experiments/local-mmr/legacy/original/` にバイト単位で保存し、source-manifest.jsonでコミットとSHA-256を固定した。変更前ビルドも `legacy/frozen-main.jar` にコピーした。原本からクラス・constructor名だけ変更した旧サービスは比較用にのみ使用し、本番からは参照しない。

共有.gitignoreは変更せず `.git/info/exclude` に `experiments/local-mmr/` を追加した。サンプル、正解ラベル、旧実装、CSV、SVG、比較ハーネスはすべてGit対象外。実ユーザーコード、APIキー、認証、送信・翻訳API、電力計測は使わない。提出済みコミット・ZIPは変更しない。

## 検証結果

2026-10-13に以下を実行した。依存は既存のローカルキャッシュを使い、実外部API呼出しは0回。

- Java 21・Maven Wrapper：`spotless:apply spotless:check verify`。205テスト成功、jar生成成功。新規13テストを含む。
- Web：`npm ci`、`npm run format:check`、`lint`、`typecheck`、`test -- --run`、`build`。55テストと全チェック成功。
- 拡張：同じ整形・Lint・型・テスト・ビルドと `npm run package`。2テスト成功、VSIX生成成功。
- Python：配布のunittest 12件、診断reader 2件成功。
- 最新Webを含むjarの `scripts/smoke_test.py`：Web配信、bridge、走査、Java解析、検索、preview、入力変更・予算不足の拒否が成功。
- `scripts/verify_api.py --scenario normal/invalid/timeout`：すべて固定providerで成功。予算・schema・参照の正常確認、不正回答・タイムアウトの拒否を確認。翻訳は認証なしの英語fallbackであり、実疎通確認ではない。
- `git diff --check` 成功。旧ロジック・実験フォルダは `git status --short --ignored` で `!! experiments/local-mmr/`、追跡対象ではないことを確認。

最初のJavaコンパイルとVite起動は環境のファイルアクセス制限で失敗した。権限制限外で同じオフライン処理を再実行し、成功を確認した。依存や処理を変更して回避していない。

専用reviewer定義を読ませたサブエージェントが読み取り専用レビューを実施した。確実な機能不具合はなし。大量候補で同じcosineを繰り返す性能懸念に対応し、最大冗長度を更新するキャッシュと再計算検出テストを追加した。再レビューでも追加不具合なし。

実ブラウザ・実VS Code操作、大量候補での応答時間、回答品質、実API tokensとの推定誤差は今回未確認。コード選択の適合率・再現率を回答の正確さと混同せず、少数の専用サンプルでの比較結果として扱う。電力削減の主張は行わない。
