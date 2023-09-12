package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.dockerController.localstack.{ LocalStackController, Service }
import com.github.j5ik2o.dockerController.{ DockerController, DockerControllerSpecSupport, WaitPredicates }
import com.github.j5ik2o.event_store_adatpter_java.internal.{ EventStoreForDynamoDB => JavaEventStoreForDynamoDB }
import org.scalatest.freespec.AnyFreeSpec
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeDefinition,
  CreateTableRequest,
  GlobalSecondaryIndex,
  KeySchemaElement,
  KeyType,
  Projection,
  ProjectionType,
  ProvisionedThroughput,
  ScalarAttributeType,
  TimeToLiveSpecification,
  UpdateTimeToLiveRequest
}

import java.net.{ URI, URL }
import java.util.UUID
import scala.concurrent.duration.Duration;

class EventStoreForDynamoDBSpec extends AnyFreeSpec with DockerControllerSpecSupport {
  val accessKeyId: String         = "AKIAIOSFODNN7EXAMPLE"
  val secretAccessKey: String     = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
  val hostPort: Int               = temporaryServerPort()
  val endpointForDynamoDB: String = s"http://$dockerHost:$hostPort"
  val region: Region              = Region.AP_NORTHEAST_1
  private val JOURNAL_TABLE_NAME  = "journal"
  private val SNAPSHOT_TABLE_NAME = "snapshot"

  private val JOURNAL_AID_INDEX_NAME  = "journal-aid-index"
  private val SNAPSHOT_AID_INDEX_NAME = "snapshot-aid-index"

  val controller: LocalStackController =
    LocalStackController(dockerClient)(
      services = Set(Service.DynamoDB),
      edgeHostPort = hostPort,
      hostNameExternal = Some(dockerHost),
      defaultRegion = Some(region.toString)
    )

  override protected val dockerControllers: Vector[DockerController] = Vector(controller)

  override protected val waitPredicatesSettings: Map[DockerController, WaitPredicateSetting] =
    Map(
      controller -> WaitPredicateSetting(Duration.Inf, WaitPredicates.forLogMessageExactly("Ready."))
    )

  val dynamodbClient: DynamoDbClient = {
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

  override protected def afterStartContainers(): Unit = {
    super.afterStartContainers()
    createJournalTable(dynamodbClient, JOURNAL_TABLE_NAME, JOURNAL_AID_INDEX_NAME)
    createSnapshotTable(dynamodbClient, SNAPSHOT_TABLE_NAME, SNAPSHOT_AID_INDEX_NAME)
  }

  "EventStore" - {
    "persistEvent" in {
      val inner = JavaEventStoreForDynamoDB.create(
        dynamodbClient,
        JOURNAL_TABLE_NAME,
        SNAPSHOT_TABLE_NAME,
        JOURNAL_AID_INDEX_NAME,
        SNAPSHOT_AID_INDEX_NAME,
        32
      )
      val eventStore         = new EventStoreForDynamoDB[UserAccountId, UserAccount, UserAccountEvent](inner)
      val id                 = UserAccountId(UUID.randomUUID().toString)
      val (aggregate, event) = UserAccount.create(id, "test-1")

      eventStore.persistEventAndSnapshot(event, aggregate)
    }
  }
}
