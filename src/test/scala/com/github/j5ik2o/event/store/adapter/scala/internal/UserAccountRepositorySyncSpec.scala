package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.dockerController.localstack.{LocalStackController, Service}
import com.github.j5ik2o.dockerController.{DockerController, DockerControllerSpecSupport, WaitPredicates}
import com.github.j5ik2o.event.store.adapter.scala.EventStore
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{OptionValues, TryValues}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.util.UUID
import scala.concurrent.duration.Duration;

class UserAccountRepositorySyncSpec
  extends AnyFreeSpec
  with DockerControllerSpecSupport
  with Matchers
  with OptionValues
  with TryValues {

  val accessKeyId: String = "AKIAIOSFODNN7EXAMPLE"
  val secretAccessKey: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
  val hostPort: Int = temporaryServerPort()
  val endpointForDynamoDB: String = s"http://$dockerHost:$hostPort"
  val region: Region = Region.AP_NORTHEAST_1

  val journalTableName = "journal"
  val snapshotTableName = "snapshot"
  val journalAidIndexName = "journal-aid-index"
  val snapshotAidIndexName = "snapshot-aid-index"

  val controller: LocalStackController =
    LocalStackController(dockerClient)(
      services = Set(Service.DynamoDB),
      edgeHostPort = hostPort,
      hostNameExternal = Some(dockerHost),
      defaultRegion = Some(region.toString),
    )

  override protected val dockerControllers: Vector[DockerController] = Vector(controller)

  override protected val waitPredicatesSettings: Map[DockerController, WaitPredicateSetting] =
    Map(
      controller -> WaitPredicateSetting(Duration.Inf, WaitPredicates.forLogMessageExactly("Ready.")),
    )

  val dynamodbClient: DynamoDbClient =
    DynamoDBUtils.dynamodbClient(endpointForDynamoDB, accessKeyId, secretAccessKey, region)

  override protected def afterStartContainers(): Unit = {
    super.afterStartContainers()
    DynamoDBUtils.createJournalTable(dynamodbClient, journalTableName, journalAidIndexName)
    DynamoDBUtils.createSnapshotTable(dynamodbClient, snapshotTableName, snapshotAidIndexName)
  }

  "UserAccountRepository" - {
    "store and findById" in {
      val eventStore = EventStore.ofDynamoDB[UserAccountId, UserAccount, UserAccountEvent](
        dynamodbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        32,
      )
      val repository = new UserAccountRepositorySync(eventStore)

      val id = UserAccountId(UUID.randomUUID().toString)
      val (aggregate1, event) = UserAccount.create(id, "test-1")
      aggregate1.sequenceNumber shouldBe 1L
      aggregate1.version shouldBe 1L

      repository.store(event, aggregate1).success.value
      val aggregate2 = repository.findById(id).success.value.value

      aggregate2 shouldBe aggregate1
      aggregate2.sequenceNumber shouldBe 1L
      aggregate2.version shouldBe 1L
    }
  }
}
