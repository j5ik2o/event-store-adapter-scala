package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.dockerController.localstack.{ LocalStackController, Service }
import com.github.j5ik2o.dockerController.{ DockerController, DockerControllerSpecSupport, WaitPredicates }
import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class EventStoreAsyncForDynamoDBSpec
    extends AnyFreeSpec
    with DockerControllerSpecSupport
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

  val testTimeFactor: Float = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toFloat
  logger.debug(s"testTimeFactor = $testTimeFactor")

  implicit val pc: PatienceConfig = PatienceConfig((30 * testTimeFactor).seconds, (1 * testTimeFactor).seconds)

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

  "EventStore" - {
    "persistEventAndSnapshot and getLatestSnapshotById" in {
      implicit val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
      val eventStore = EventStoreAsync.ofDynamoDB[UserAccountId, UserAccount, UserAccountEvent](
        dynamodbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        32
      )

      val id                 = UserAccountId(UUID.randomUUID().toString)
      val (aggregate, event) = UserAccount.create(id, "test-1")

      eventStore.persistEventAndSnapshot(event, aggregate).futureValue

      val (userAccount, _) = eventStore.getLatestSnapshotById(classOf[UserAccount], id).futureValue.value
      assert(userAccount.id == id)
      assert(userAccount.name == "test-1")

    }
  }
}
