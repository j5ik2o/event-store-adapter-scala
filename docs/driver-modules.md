# Choose storage dependencies

The compatibility artifact `event-store-adapter-scala` depends on every Scala storage module. Existing packages, types, and factory signatures remain available.

For DynamoDB deployments, depend on `event-store-adapter-scala-dynamodb` instead. It brings in `event-store-adapter-scala-core` and the Java 5.2.0 DynamoDB driver. It does not bring in Spanner, Bigtable, or SQLite at runtime.

```scala
libraryDependencies += "io.github.j5ik2o" %% "event-store-adapter-scala-dynamodb" % version
```

Use `DynamoDbEventStores.of(client, tables, config)` for synchronous stores and `DynamoDbEventStores.ofAsync(client, tables, config)` for asynchronous stores. The caller owns the SDK client.

The other modules and factories follow the same structure.

| Artifact suffix | Factory | Arguments |
| --- | --- | --- |
| `core` | `EventStore.ofMemory`, `EventStoreAsync.ofMemory` | config, or storage and config |
| `dynamodb` | `DynamoDbEventStores.of`, `DynamoDbEventStores.ofAsync` | client, tables, config |
| `spanner` | `SpannerEventStores.of`, `SpannerEventStores.ofAsync` | storage, config |
| `bigtable` | `BigtableEventStores.of`, `BigtableEventStores.ofAsync` | storage, config |
| `sqlite` | `SqliteEventStores.of`, `SqliteEventStores.ofAsync` | storage, config |

All factories remain in `com.github.j5ik2o.event.store.adapter.scala`. The legacy storage factories on `EventStore` and `EventStoreAsync` are deprecated. Core compiles these signatures against Java drivers in Provided scope. Applications using a legacy storage factory must include its Scala driver module or the compatibility artifact.

Run `sbt test` to include the independent core-only and core-with-DynamoDB classpath tests. They run with ordinary JVM verification. The DynamoDB test provisions its own DynamoDB Local container, checks both factory paths, and rejects the 409512-byte D-7 regression input before creating a head. The distributed conformance suite tests all four near-limit event and snapshot cases through Scala driver factories.
