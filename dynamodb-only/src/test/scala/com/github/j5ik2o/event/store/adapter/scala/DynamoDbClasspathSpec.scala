package com.github.j5ik2o.event.store.adapter.scala

import com.github.j5ik2o.event.store.adapter.java.core.{
  AggregateId,
  ContractViolationException,
  EventEnvelope,
  EventStoreConfig,
  PayloadSerializer,
}
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import org.scalatest.freespec.AnyFreeSpec
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbClient}
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI
import java.time.Instant
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class DynamoDbClasspathSpec extends AnyFreeSpec {
  "core and DynamoDB initialize both companions and read/write through legacy and driver factories" in {
    Seq("spanner.SpannerStorage", "bigtable.BigtableStorage", "sqlite.SqliteStorage").foreach { name =>
      intercept[ClassNotFoundException](Class.forName("com.github.j5ik2o.event.store.adapter.java." + name))
    }
    Seq("com.google.cloud.spanner.Spanner", "com.google.cloud.bigtable.data.v2.BigtableDataClient", "org.sqlite.JDBC")
      .foreach { name =>
        intercept[ClassNotFoundException](Class.forName(name))
      }
    val serializer = new PayloadSerializer[Array[Byte]] {
      override def serialize(value: Array[Byte]): Array[Byte] = value
      override def deserialize(value: Array[Byte]): Array[Byte] = value
    }
    val config = EventStoreConfig
      .builder[Array[Byte], Array[Byte]]()
      .payloadSerializer(serializer)
      .snapshotSerializer(serializer)
      .build()
    EventStore.ofMemory(config).get
    Await.result(EventStoreAsync.ofMemory(config), 30.seconds)
    val container = new GenericContainer[Nothing](
      DockerImageName.parse(
        "amazon/dynamodb-local:3.3.2@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab"))
    container.withExposedPorts(Integer.valueOf(8000))
    container.withCommand("-jar", "DynamoDBLocal.jar", "-inMemory")
    container.withCreateContainerCmdModifier { cmd =>
      cmd.withName("esa-scala-java520-" + java.util.UUID.randomUUID()); ()
    }
    container.start()
    try {
      val endpoint = URI.create(s"http://${container.getHost}:${container.getMappedPort(8000)}")
      val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local"))
      val client = DynamoDbClient
        .builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .build()
      val async = DynamoDbAsyncClient
        .builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .credentialsProvider(credentials)
        .build()
      try {
        def attribute(name: String, kind: ScalarAttributeType): AttributeDefinition =
          AttributeDefinition.builder().attributeName(name).attributeType(kind).build()
        def key(name: String, kind: KeyType): KeySchemaElement =
          KeySchemaElement.builder().attributeName(name).keyType(kind).build()
        Seq(("journal", "seq_nr"), ("snapshot", "skey"), ("head", "")).foreach { case (name, sort) =>
          val request = CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
          if (sort.isEmpty)
            request
              .attributeDefinitions(attribute("aid", ScalarAttributeType.S))
              .keySchema(key("aid", KeyType.HASH))
              .streamSpecification(
                StreamSpecification.builder().streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build())
          else
            request
              .attributeDefinitions(attribute("aid", ScalarAttributeType.S), attribute(sort, ScalarAttributeType.N))
              .keySchema(key("aid", KeyType.HASH), key(sort, KeyType.RANGE))
          if (name == "snapshot")
            request
              .attributeDefinitions(
                attribute("aid", ScalarAttributeType.S),
                attribute(sort, ScalarAttributeType.N),
                attribute("active_history_seq_nr", ScalarAttributeType.N))
              .globalSecondaryIndexes(
                GlobalSecondaryIndex
                  .builder()
                  .indexName("history")
                  .keySchema(key("aid", KeyType.HASH), key("active_history_seq_nr", KeyType.RANGE))
                  .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                  .build())
          client.createTable(request.build())
        }
        val tables = DynamoDbTableConfig
          .builder()
          .journalTableName("journal")
          .snapshotTableName("snapshot")
          .headTableName("head")
          .snapshotAidIndexName("history")
          .build()
        implicit val ec: ExecutionContext = ExecutionContext.global
        val syncStores =
          Seq(EventStore.ofDynamoDB(client, tables, config).get, DynamoDbEventStores.of(client, tables, config).get)
        val asyncStores = Seq(
          Await.result(EventStoreAsync.ofDynamoDB(async, tables, config), 30.seconds),
          Await.result(DynamoDbEventStores.ofAsync(async, tables, config), 30.seconds))
        def event(id: String, size: Int) = EventEnvelope
          .builder[Array[Byte]]()
          .aggregateId(AggregateId.of("Order", id))
          .seqNr(1L)
          .occurredAt(Instant.parse("2025-11-10T04:46:40Z"))
          .manifest("m")
          .payload(new Array[Byte](size))
          .build()
        def assertRejectedHeadAbsent(): Unit = {
          val request = GetItemRequest
            .builder()
            .tableName("head")
            .key(java.util.Collections.singletonMap("aid", AttributeValue.builder().s("Order-9").build()))
            .consistentRead(true)
            .build()
          assert(!client.getItem(request).hasItem())
        }
        syncStores.zipWithIndex.foreach { case (store, i) =>
          val e = event("sync" + i, 3)
          store.persistEvent(e).get
          assert(store.getEventsByIdSinceSeqNr(e.aggregateId(), 1L).get.map(_.seqNr()) == Seq(1L))
          val rejected = store.persistEvent(event("9", 409512)).failed.get
          assert(rejected.isInstanceOf[ContractViolationException], rejected.toString)
          assertRejectedHeadAbsent()
          assert(store.getLatestSnapshotById(AggregateId.of("Order", "9")).get.isEmpty)
        }
        asyncStores.zipWithIndex.foreach { case (store, i) =>
          val e = event("async" + i, 3)
          Await.result(store.persistEvent(e), 30.seconds)
          assert(Await.result(store.getEventsByIdSinceSeqNr(e.aggregateId(), 1L), 30.seconds).map(_.seqNr()) == Seq(1L))
          intercept[ContractViolationException](Await.result(store.persistEvent(event("9", 409512)), 30.seconds))
          assertRejectedHeadAbsent()
        }
      } finally { async.close(); client.close() }
    } finally container.stop()
  }
}
