# Issue #4 Java解析サンプル

このフォルダは解析専用です。ビルド・実行・依存のインストールは不要です。
Spring/JPA/Securityのimportには外部依存を同梱していません。

1. Webでこのフォルダを選択して走査します。
2. 「Java解析」を押し、解析結果を展開します。
3. Controller→Service→Repositoryのメソッド呼出し、constructor/field DI、型参照を確認します。
4. `OrderService.find(int)` と `find(String)` が異なるメソッドID・署名で抽出されます。
5. `OrderDto` はrecord/DTO、`SecurityConfig` はConfiguration/Securityとして分類されます。
6. `Broken.java` は解析失敗になります。他の6ファイルは解析を継続します。
7. `UnknownSecurityBuilder` と `builder.build()` は未解決です。「依存なし」ではありません。

行番号は1始まり・両端を含みます。解析するファイルは走査時の内容に固定され、
ファイル変更後の内容を見る場合は「再走査」→「Java解析」を行います。
`RESOLVED` はSymbolSolverの解決結果、`HEURISTIC` は明示されたレシーバ型と署名の一意候補です。
役割はannotation/typeの構文上の根拠を表示し、外部依存を完全に解決した証明ではありません。
