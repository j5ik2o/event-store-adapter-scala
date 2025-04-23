package com.github.j5ik2o.event.store.adapter.scala.internal

import com.github.j5ik2o.dockerController.localstack.{LocalStackController, Service}
import com.github.j5ik2o.dockerController.{DockerController, DockerControllerSpecSupport, WaitPredicates}
import com.github.j5ik2o.event.store.adapter.scala.EventStoreAsync
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class UserAccountRepositoryAsyncSpec
  extends AnyFreeSpec
  with DockerControllerSpecSupport
  with Matchers
  with OptionValues
  with ScalaFutures {

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

  val testTimeFactor: Float = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toFloat
  logger.debug(s"testTimeFactor = $testTimeFactor")

  implicit val pc: PatienceConfig =
    PatienceConfig((30 * testTimeFactor).toInt.seconds, (1 * testTimeFactor).toInt.seconds)

  override protected val dockerControllers: Vector[DockerController] = Vector(controller)

  override protected val waitPredicatesSettings: Map[DockerController, WaitPredicateSetting] =
    Map(
      controller -> WaitPredicateSetting(Duration.Inf, WaitPredicates.forLogMessageExactly("Ready.")),
    )

  val dynamodbAsyncClient: DynamoDbAsyncClient =
    DynamoDBUtils.dynamodbAsyncClient(endpointForDynamoDB, accessKeyId, secretAccessKey, region)

  override protected def afterStartContainers(): Unit = {
    super.afterStartContainers()
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
    DynamoDBUtils.createJournalTableAsync(dynamodbAsyncClient, journalTableName, journalAidIndexName)
    DynamoDBUtils.createSnapshotTableAsync(dynamodbAsyncClient, snapshotTableName, snapshotAidIndexName)
  }

  "UserAccountRepositoryAsync" - {
    "store and findById" in {
      implicit val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
      val eventStore = EventStoreAsync.ofDynamoDB[UserAccountId, UserAccount, UserAccountEvent](
        dynamodbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        32,
      )
      val repository = new UserAccountRepositoryAsync(eventStore)

      val id = UserAccountId(UUID.randomUUID().toString)
      val name = "test-1"
      val (aggregate1, event) = UserAccount.create(id, name)
      aggregate1.sequenceNumber shouldBe 1L
      aggregate1.version shouldBe 1L

      repository.store(event, aggregate1).futureValue
      val aggregate2 = repository.findById(id).futureValue.value

      aggregate2 shouldBe aggregate1
      aggregate2.sequenceNumber shouldBe 1L
      aggregate2.version shouldBe 1L
    }
  }
}
