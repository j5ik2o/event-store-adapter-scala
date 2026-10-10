package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.core.{
  ConfigurationException,
  EventStoreConfig,
  JsonPayloadSerializer,
  StorageException,
}
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DeferredConfigurationClient
import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

final class AsyncCreationSpec extends AnyFreeSpec {
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private val config: EventStoreConfig[String, String] = EventStoreConfig
    .builder[String, String]()
    .payloadSerializer(JsonPayloadSerializer.of(classOf[String]))
    .snapshotSerializer(JsonPayloadSerializer.of(classOf[String]))
    .build()
  private val tables: DynamoDbTableConfig = DynamoDbTableConfig
    .builder()
    .journalTableName("journal")
    .snapshotTableName("snapshot")
    .headTableName("head")
    .snapshotAidIndexName("history")
    .build()

  "DynamoDB creation remains pending until the same SDK request finishes" in {
    val sdk = new DeferredConfigurationClient()
    val creation = EventStoreAsync.ofDynamoDB(sdk.client(), tables, config)
    assert(sdk.requests() == 1)
    assert(!creation.isCompleted)
    sdk.succeed()
    Await.result(creation, 3.seconds)
    assert(sdk.requests() == 1 && !sdk.closed())
    sdk.client().close()
    assert(sdk.closed())
  }

  "DynamoDB creation keeps a classified asynchronous failure and its original cause" in {
    val sdk = new DeferredConfigurationClient()
    val cause = new IllegalStateException("SDK completion failure")
    val original = new StorageException("configuration communication failed", cause)
    val creation = EventStoreAsync.ofDynamoDB(sdk.client(), tables, config)
    sdk.fail(original)
    val failure = Await.ready(creation, 3.seconds).value.get.failed.get
    assert(failure eq original)
    assert(failure.getCause eq cause)
    assert(!sdk.closed())
    sdk.client().close()
  }

  "DynamoDB invalid factory arguments yield a failed Future" in {
    val missing = Option.empty[software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient].orNull
    val creation = EventStoreAsync.ofDynamoDB(missing, tables, config)
    assert(Await.ready(creation, 3.seconds).value.get.failed.get.isInstanceOf[ConfigurationException])
  }
}
