package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.PublicApiTestDatabase
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbClient}

object DynamoDBUtils {
  def withTables[A](test: (DynamoDbClient, DynamoDbAsyncClient, DynamoDbTableConfig) => A): A = {
    val database = new PublicApiTestDatabase()
    try test(database.client(), database.asyncClient(), database.tables())
    finally database.close()
  }
}
