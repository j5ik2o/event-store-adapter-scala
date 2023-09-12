package com.github.j5ik2o.event.store.adapter.scala.internal

import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI

object DynamoDBUtils {

  def dynamodbClient(
      endpointForDynamoDB: String,
      accessKeyId: String,
      secretAccessKey: String,
      region: Region
  ): DynamoDbClient = {
    DynamoDbClient
      .builder()
      .endpointOverride(URI.create(endpointForDynamoDB))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
      .region(region)
      .build();
  }

  def createJournalTable(client: DynamoDbClient, tableName: String, indexName: String): Unit = {
    val pt = ProvisionedThroughput.builder.readCapacityUnits(10L).writeCapacityUnits(5L).build
    client.createTable(
      CreateTableRequest.builder
        .tableName(tableName).attributeDefinitions(
          AttributeDefinition.builder.attributeName("pkey").attributeType(ScalarAttributeType.S).build,
          AttributeDefinition.builder.attributeName("skey").attributeType(ScalarAttributeType.S).build,
          AttributeDefinition.builder.attributeName("aid").attributeType(ScalarAttributeType.S).build,
          AttributeDefinition.builder.attributeName("seq_nr").attributeType(ScalarAttributeType.N).build
        ).keySchema(
          KeySchemaElement.builder.attributeName("pkey").keyType(KeyType.HASH).build,
          KeySchemaElement.builder.attributeName("skey").keyType(KeyType.RANGE).build
        ).globalSecondaryIndexes(
          GlobalSecondaryIndex.builder
            .indexName(indexName).keySchema(
              KeySchemaElement.builder.attributeName("aid").keyType(KeyType.HASH).build,
              KeySchemaElement.builder.attributeName("seq_nr").keyType(KeyType.RANGE).build
            ).projection(Projection.builder.projectionType(ProjectionType.ALL).build).provisionedThroughput(pt).build
        ).provisionedThroughput(pt).build
    )
  }

  def createSnapshotTable(client: DynamoDbClient, tableName: String, indexName: String): Unit = {
    val pt = ProvisionedThroughput.builder.readCapacityUnits(10L).writeCapacityUnits(5L).build
    client.createTable(
      CreateTableRequest.builder
        .tableName(tableName).attributeDefinitions(
          AttributeDefinition.builder.attributeName("pkey").attributeType(ScalarAttributeType.S).build,
          AttributeDefinition.builder.attributeName("skey").attributeType(ScalarAttributeType.S).build,
          AttributeDefinition.builder.attributeName("aid").attributeType(ScalarAttributeType.S).build,
          AttributeDefinition.builder.attributeName("seq_nr").attributeType(ScalarAttributeType.N).build
        ).keySchema(
          KeySchemaElement.builder.attributeName("pkey").keyType(KeyType.HASH).build,
          KeySchemaElement.builder.attributeName("skey").keyType(KeyType.RANGE).build
        ).globalSecondaryIndexes(
          GlobalSecondaryIndex.builder
            .indexName(indexName).keySchema(
              KeySchemaElement.builder.attributeName("aid").keyType(KeyType.HASH).build,
              KeySchemaElement.builder.attributeName("seq_nr").keyType(KeyType.RANGE).build
            ).projection(Projection.builder.projectionType(ProjectionType.ALL).build).provisionedThroughput(pt).build
        ).provisionedThroughput(pt).build
    )
    client.updateTimeToLive(
      UpdateTimeToLiveRequest.builder
        .tableName(tableName).timeToLiveSpecification(
          TimeToLiveSpecification.builder.enabled(true).attributeName("ttl").build
        ).build
    )
  }
}
