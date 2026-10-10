# Scala 1.xの公開操作と保存データの移行

Java依存は`2.0.0-SNAPSHOT`です。Scalaの成果物版は既存のsbt-dynver設定から生成します。共通契約v4、配布データ1.0.0、DynamoDBの`layout_version=1`は別の番号です。

## 公開操作の変更

| 旧Scalaの公開形 | 新Scalaの公開形 |
|---|---|
| `EventStore[AID,A,E]`、`EventStoreAsync[AID,A,E]` | `EventStore[P,A]`、`EventStoreAsync[P,A]` |
| ストアを直接返す生成入口 | 同期生成は`Try`、非同期生成は`Future` |
| ドメインが実装する`Aggregate`、`Event`、`AggregateId` | 任意の直列化可能なドメイン型、Javaの封筒、`AggregateId.of(typeName, value)` |
| `with...`設定と旧シリアライザ | 生成時の`EventStoreConfig`、`PayloadSerializer<T>`、保存先の保持方針 |
| 2表、シャード数、キー解決器、ジャーナルの索引 | 3表とスナップショット履歴索引を指定する`DynamoDbTableConfig` |
| `persistEvent(event, version)` | `persistEvent(eventEnvelope)` |
| `persistEventAndSnapshot(event, aggregate)` | `persistEventAndSnapshot(eventEnvelope, snapshotEnvelope)` |
| `getLatestSnapshotById(Class, id)` | 二段の不存在を返す`getLatestSnapshotById(id)` |
| `getEventsByIdSinceSequenceNumber(Class, id, start)` | 全封筒を返す`getEventsByIdSinceSeqNr(id, start)` |
| 旧読取・書込・復元例外 | Javaの`core`の5分類例外。復元失敗も`SerializationException` |

イベントと集約状態のシリアライザは生成時に決めます。直列化するのはドメイン内容だけです。集約識別子、番号、発生時刻、manifestは封筒に分けます。manifestはライブラリで解釈しません。型名と識別子の値は確認済みの対応を使い、旧文字列から推測しません。

番号1で集約を作り、以後はヘッド直後の連続した番号を使います。期待version引数は渡しません。同時に保存するイベントとスナップショットは同じ番号です。重複・古い番号は楽観ロック、飛び番は契約違反です。契約違反は`rule()`と`seqNr()`を持ちます。

最新読取の外側の`None`はヘッド不存在です。結果があり`snapshot=None`なら、既存集約にスナップショットがないため番号1から再生します。スナップショットがあれば`snapshot.seqNr()+1`から再生します。ヘッドより新しいスナップショットも保持し、`headSeqNr`を開始番号や上界にしません。[復元例](README.ja.md#保存と復元)を参照してください。

## 旧データを書き直す手順

新版は新配置を読みます。移行は、旧版による読取り、アプリケーションの明示変換、独立した新表への新封筒による書直しで行います。

1. 旧保存先への書込みを制御し、旧データと確認済みのドメイン・スキーマ情報を移行中も保持します。
2. 旧Scala・Javaライブラリと既存シリアライザで、集約ごとの全イベントを読みます。旧スナップショットを使う場合は、その状態と番号を確認します。
3. 旧識別子を確認済みの型名と値へ対応付けます。ペイロードと封筒メタデータを分け、確認済みの時刻とスキーマに基づくmanifestを設定します。書込み前に、番号1から全イベントを連続して書けることを確認します。欠番や情報欠落は実データから解決し、推測値を挿入しません。
4. [新スキーマ](docs/DATABASE_SCHEMA.ja.md)で独立したjournal・snapshot・headの3表を作ります。旧表と新表を混在させません。期限付き保持を使う場合だけsnapshot表の`ttl`を有効にします。
5. `EventStore.ofDynamoDB(client, tables, config)`の`Try`、または`EventStoreAsync.ofDynamoDB(asyncClient, tables, config)`の生成Futureを確認します。生成入口が同じ保存先識別子と配置番号を持つ3設定項目を初期化・照合します。
6. 番号を昇順・連続にして`persistEvent`で書きます。変換したスナップショットは、同番号のイベントを追記するときに`persistEventAndSnapshot`で保存します。スナップショットだけの書込みはありません。旧version・シャードキー・メタデータ入りJSONをそのままコピーしません。
7. 全封筒を読み戻し、新公開操作で復元します。確認済みの変換ペイロード、番号、時刻、manifest、状態と比較します。分類された失敗を空結果で隠しません。
8. 書直しの検証後にアプリケーションの読書き先を切り替えます。旧表を削除する時期は利用者が決め、この案内から削除は実行しません。

`Instant`へ変換する前に、旧データの実際の時刻表現と精度を確認します。ミリ秒だけを持つ場合は、そのミリ秒を正確に表します。旧時刻単位を推測せず、失われたナノ秒、型情報、識別子を推測値や現在時刻で補いません。

書直しの途中で中断した場合は、確定済み番号を読んでから再開します。確定済み番号の再追記は楽観ロックになります。新版に旧形式のフォールバック読取りを追加しません。

## 保存先と保持

同期・非同期インスタンス間で状態を共有する場合は同じ`MemoryStorage`を渡します。省略生成は毎回隔離されます。Memoryに期限付き保持と変更フィードはありません。

保存先で`RetentionPolicy.none()`、`delete(n)`、DynamoDBの場合は`ttl(n, graceSeconds)`を設定します。件数は1以上、猶予は非負の整数秒です。既定は現在のスナップショットだけです。期限の印付き履歴は保持件数に数えず、期限も延長しません。

確定後の保持失敗は書込み成功を変えず、別のログ・通知経路で元原因を知らせます。保存と再試行はJavaが所有し、クライアントは利用者が所有・終了します。
