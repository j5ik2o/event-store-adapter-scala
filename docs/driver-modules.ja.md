> この文書は [driver-modules.md](driver-modules.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# 保存先依存の選び方

互換用の成果物 `event-store-adapter-scala` は、すべてのScala保存先モジュールに依存します。既存のパッケージ、公開型、生成入口のシグネチャは維持します。

DynamoDBだけを配布するアプリケーションでは、`event-store-adapter-scala-dynamodb` を指定します。ScalaのcoreとJava 5.2.0のDynamoDBドライバを取り込み、Spanner、Bigtable、SQLiteは実行時の依存に含めません。

```scala
libraryDependencies += "io.github.j5ik2o" %% "event-store-adapter-scala-dynamodb" % version
```

同期ストアは `DynamoDbEventStores.of(client, tables, config)`、非同期ストアは `DynamoDbEventStores.ofAsync(client, tables, config)` で生成します。SDKクライアントは呼出元が所有します。

ほかのモジュールと生成入口も同じ構成です。

| 成果物の接尾辞 | 生成入口 | 引数 |
| --- | --- | --- |
| `core` | `EventStore.ofMemory`, `EventStoreAsync.ofMemory` | config、またはstorageとconfig |
| `dynamodb` | `DynamoDbEventStores.of`, `DynamoDbEventStores.ofAsync` | client, tables, config |
| `spanner` | `SpannerEventStores.of`, `SpannerEventStores.ofAsync` | storage, config |
| `bigtable` | `BigtableEventStores.of`, `BigtableEventStores.ofAsync` | storage, config |
| `sqlite` | `SqliteEventStores.of`, `SqliteEventStores.ofAsync` | storage, config |

生成入口はすべて `com.github.j5ik2o.event.store.adapter.scala` にあります。`EventStore` と `EventStoreAsync` の既存の保存先生成入口は非推奨です。coreは既存シグネチャをJavaドライバへのProvided依存でコンパイルします。既存の生成入口を使うアプリケーションも、該当するScalaドライバか互換用成果物を指定します。

`sbt test` はcore単独とcoreにDynamoDBを加えたクラスパスを独立して検査します。JVMの検証設定は通常どおりです。DynamoDBの試験は専用のDynamoDB Localコンテナを作り、既存と新規の生成入口を検査します。409512バイトのD-7回帰入力がheadを作る前に拒否されることも確かめます。配布された適合テストは、Scalaドライバの生成入口を通してeventとsnapshotの上限付近4件を検査します。
