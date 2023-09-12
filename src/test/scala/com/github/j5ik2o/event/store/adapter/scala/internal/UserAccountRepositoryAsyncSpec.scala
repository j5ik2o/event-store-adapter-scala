package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.dockerController.localstack.{ LocalStackController, Service }
import com.github.j5ik2o.dockerController.{ DockerController, DockerControllerSpecSupport, WaitPredicates }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.{ AnyFreeSpec, AsyncFreeSpec }
import org.scalatest.matchers.should.Matchers
import org.scalatest.{ OptionValues, TryValues }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ Duration, DurationInt };

class UserAccountRepositoryAsyncSpec
    extends AnyFreeSpec
    with DockerControllerSpecSupport
    with Matchers
    with OptionValues
    with ScalaFutures {

  val accessKeyId: String         = "AKIAIOSFODNN7EXAMPLE"
  val secretAccessKey: String     = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
  val hostPort: Int               = temporaryServerPort()
  val endpointForDynamoDB: String = s"http://$dockerHost:$hostPort"
  val region: Region              = Region.AP_NORTHEAST_1

  val journalTableName     = "journal"
  val snapshotTableName    = "snapshot"
  val journalAidIndexName  = "journal-aid-index"
  val snapshotAidIndexName = "snapshot-aid-index"

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

  val dynamodbClient: DynamoDbClient =
    DynamoDBUtils.dynamodbClient(endpointForDynamoDB, accessKeyId, secretAccessKey, region)

  override protected def afterStartContainers(): Unit = {
    super.afterStartContainers()
    DynamoDBUtils.createJournalTable(dynamodbClient, journalTableName, journalAidIndexName)
    DynamoDBUtils.createSnapshotTable(dynamodbClient, snapshotTableName, snapshotAidIndexName)
  }

  implicit val pc: PatienceConfig = PatienceConfig(30.seconds, 1.seconds)

  "UserAccountRepositoryAsync" - {
    "store and findById" in {
      implicit val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
      val eventStore = EventStoreAsyncForDynamoDB[UserAccountId, UserAccount, UserAccountEvent](
        dynamodbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        32
      )
      val repository = new UserAccountRepositoryAsync(eventStore)

      val id                 = UserAccountId(UUID.randomUUID().toString)
      val (aggregate, event) = UserAccount.create(id, "test-1")

      repository.store(event, aggregate).futureValue
      repository.findById(id).futureValue.value shouldBe aggregate

    }
  }
}
