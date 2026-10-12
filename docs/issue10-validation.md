# Issue #10：導入ガイド・提出梱包の先行検証

## 範囲と前提

最新main `aa2b99a`（Issue #7まで）を基に、`feature/issue10-distribution`でガイドと梱包だけを整備する。Issue #14 PR #17は未マージの前提PRであり、デモ認証・固定回答の重複実装は行わない。#8翻訳、#9会話/TXT、完成版サンプル・比較実験は未実装。

詳細設計3.2/3.3、16、21を確認した。Issue #10の全完了条件である「キー手入力不要で日本語回答まで」「翻訳認証確認」「完成版デモ」「再起動後TXT引継ぎ」は今回達成していない。PRは`Refs #10`の先行整備として扱い、`Closes #10`を付けない。

## 変更

- `docs/installation-guide.md`：ZIP展開、前提、VSIX導入、起動・接続、既存サンプルの質問・主選択、検索・概算、任意の実生成、停止・再起動、エラー時の操作、未実装事項を記載。
- READMEから導入ガイドへの案内を追加。
- package.ps1：クリーンな作業ツリーとcommit確認→既存build.ps1で再ビルド→ソースが変化していないことを再確認→梱包。以前のjar/VSIXは再ビルドなしで再利用しない。
- package_distribution.py：git archiveのtrackedソースを許可リストで選定。選定前にtracked秘密・危険パスも拒否。認証JSON構造、代表的なキー/秘密鍵形式、値入り.env.exampleを拒否。jar/VSIXは期待するアプリファイルと内部エントリーを検査し、CRC検査後に完成ZIPを原子的に置換する。
- ZIPにはjar、VSIX、samples、Markdown設計・導入文書、ソース、scripts、CI設定、commitとファイル一覧のmanifestを同梱。Word原本、エディタ設定、.env、runtime、認証JSON、依存キャッシュ、node_modules、ログ、利用者コードは含めない。空欄.env.exampleだけ許可。

依存ライブラリのnested jarは展開・再梱包しない。全JSONを一律に除外せず、アプリschemaやpackage-lockは保持する。秘密判定は完全なDLPではなく、任意の別名・独自形式の秘密を完全に保証するものではない。提出者のレビューは必要。

## 自動検証（2026-10-11）

| コマンド・確認 | 結果 |
| --- | --- |
| `python -m unittest discover -s scripts -p test_package_distribution.py -v` | 9件成功。すべて偽のfixtureのみ |
| `python -m py_compile scripts/package_distribution.py scripts/test_package_distribution.py` | 成功 |
| PowerShell Parserの`ParseFile`でpackage.ps1解析 | エラー0件 |
| HEADのgit archiveをsource_entriesへ渡す読み取り専用検査 | 162ファイルを選定、拒否なし |
| `git diff --check` | 成功（Windows改行のGit通知のみ） |

テスト対象：.env/runtime/秘密鍵/依存/path traversal排除、空設定例、別名/入れ子の認証JSON、キー値非出力、許可リスト外のtracked秘密による中止、Word/エディタ設定の除外、artifact内部runtime拒否、通常のjar/VSIX構造、最終ZIPとmanifestの整合、失敗時の既存ZIP保持。

Ruffは導入されていないため、追加インストール・ruff format実行はしていない。Java/Web/拡張の処理変更はないため本作業で再ビルドを反復していない。

## 未確認・手動受入チェック

初回レビュー時点では未コミットのため実梱包は未実施だった。その後、親が`b0894e3`でWindows PowerShell 5.1のpackage.ps1を実行し成功した。既存のJDK 21・Maven/npmキャッシュを使い、実APIを呼ばずにWebの整形・Lint・型チェック・30テスト・ビルド、JavaのSpotless・clean verify・104テスト、拡張の整形・Lint・型チェック・2テスト・ビルド・VSIX生成を確認した。

実jar/VSIXの安全検査、最終ZIP生成、manifest、公開canary4件の生成先・jar/VSIXからの除去も成功。生成ZIPのsourceCommitは`b0894e3`で、この後追記した検証記録を含むcommitのZIPを作成したとは主張しない。最新CIの結果はPRに記録する。VSIXの既存repository/LICENSE警告は残るが梱包は成功した。

- [ ] 新しいZIPを別フォルダに展開し、manifestのcommitとjar/VSIXを確認。
- [ ] 実VS CodeでVSIXを導入し、Open Webから接続。
- [ ] samples/code-searchを走査し、指定した質問・主選択・候補・previewを確認。
- [ ] Ctrl+Cで停止し、再起動・再接続・再走査。
- [ ] #14マージ後にデモ用ローカル認証と固定応答の手順を統合。
- [ ] #8で翻訳認証・日本語回答、#9で履歴/TXT引継ぎを確認。
- [ ] Issue #10全体の受入条件を、後続機能が揃ってから再評価。

実API通信はこの作業で行っていない。#14の許可回数を消費していない。

親の実梱包確認で、PowerShell 5.1がnpmのstderr警告を終了コード0でも例外扱いし、ビルドを停止する問題を確認した。Run内だけnative stderrを表示して続行し、非ゼロ終了コードで明示停止するよう修正した。公開warning fixtureとexit 3を使い、警告の続行・失敗時の停止・ErrorActionPreference復元を確認した。CIの通常ビルドもWindows PowerShell 5.1で実行する。

前提PRの互換性確認：#14作業ブランチのtrackedファイルだけを`git archive`で検査し、178ファイルが選定・検査を通過した。.envやユーザー認証は読み込んでいない。verify_api.pyのfixture文字列を現在の検査は誤検出しなかった。前提PR合流後には、実jar/VSIX内部も同様に再確認する（未実施）。

## レビュー指摘P2への対応：古い生成物の残存

指摘は妥当。以前のbuild.ps1はstaticを上書きし、Maven verify/tscだけだったため、削除済みasset/class/extension JSが残り得た。staticとextension/distを生成専用ディレクトリとして初期化し、Mavenをclean verifyへ変更した。削除前にworkspace内の絶対パス、許可されたディレクトリ名、親と内部のreparsepointを確認し、junction/symlinkがあれば中止する。解析対象や利用者コードは初期化しない。build.ps1はWindows PowerShell 5.1用UTF-8 BOMを保持する。

Vite設定はoutDir/emptyOutDirを上書きしていないため、標準のproject内dist初期化を使用する。これだけを成功の根拠とせず、`verify_distribution_clean.py seed`→通常のbuild.ps1→`verify_distribution_clean.py check`を既存CIへ追加する。canaryはweb/dist、Spring static、Maven target/classes、extension/distに置き、生成先と最終jar/VSIXから消えることを検査する。追加依存・追加のnpm ciは不要。

追加fixtureテスト3件成功（合計12件）。生成先のみのseed、既存canary非上書き、生成先/成果物内残存の拒否とclean判定を検証した。PowerShell 5.1でcleanup関数を偽のworkspaceへ適用し、static/extensionのcanary削除と許可リスト外拒否を確認した。親の実梱包とCIの通常ビルドでもcanary除去を確認済み。

## 2026-10-12：前提PRと配布対象の更新

利用者指示により、#14の接続・実ブラウザ受入は保留し、#8翻訳と#9会話/TXTを別PRで並行実装する。マージ順は17→8→9→19。PR19は#7ベースのままで前提の未マージ機能を取り込まず、合流後の最新mainによる整合確認・実ZIP再作成を必須の残作業とする。draft/Refs #10を維持し、Closes #10は付けない。

導入ガイドに翻訳のbackend環境設定名・認証JSONのプロジェクト外保管・未設定時/再翻訳の確認項目、TXT引継ぎの受入項目を追記した。これは前提PR合流後の確認計画であり、このブランチで利用できる機能の追加ではない。

梱包検査に.playwright-mcp/validation-results/user-workspacesを禁止ディレクトリとして追加。公開の専用サンプルcase.jsonと固定回答fixtureは保持し、実回答や利用者会話とは区別するfixtureを追加した。実キー・認証JSON・実回答は読み込まず、実API通信は行っていない。

`python -m unittest discover -s scripts -p 'test_*distribution*.py' -v`の12件、変更Pythonの`py_compile`、`git diff --check`が成功。変更は梱包判定と文書・fixtureに限定し、Java/Web/拡張の再ビルドはこの追記では行っていない。最新push後の通常ビルド・既存チェックはCIで確認する。
