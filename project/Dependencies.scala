import sbt._

object Dependencies {

  object Versions {
    val scala213Version = "2.13.12"
    val scala3Version   = "3.3.1"

    val logbackVersion  = "1.4.11"
    val slf4jVersion    = "1.7.36"
    val awsSdkV2Version = "2.21.1"

    val scalaTest32Version = "3.2.17"

  }

  import Versions._

  object fasterxml {
    val jacksonModuleScala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.3"
  }

  object j5ik2o {
    val dockerController_ScalaTest  = "com.github.j5ik2o" %% "docker-controller-scala-scalatest"  % "1.15.34"
    val dockerController_LocalStack = "com.github.j5ik2o" %% "docker-controller-scala-localstack" % "1.15.34"
    val eventStoreAdapterJava       = "com.github.j5ik2o"  % "event-store-adapter-java"           % "1.1.14"
  }

  object softwareamazon {
    val dynamodb = "software.amazon.awssdk" % "dynamodb" % awsSdkV2Version
  }

  object logback {
    val classic = "ch.qos.logback" % "logback-classic" % logbackVersion
  }

  object scalatest {
    val scalatest = "org.scalatest" %% "scalatest" % scalaTest32Version
  }

}
