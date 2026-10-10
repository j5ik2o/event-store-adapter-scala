import sbt._

object Dependencies {

  object Versions {
    val scala213Version = "2.13.18"
    val scala3Version = "3.6.4"

    val logbackVersion = "1.6.5"

    val scalaTest32Version = "3.2.20"

  }

  import Versions._

  object j5ik2o {
    val eventStoreAdapterJava = "io.github.j5ik2o" % "event-store-adapter-java" % "2.0.0-SNAPSHOT"
  }

  object testSupport {
    val junitApi = "org.junit.jupiter" % "junit-jupiter-api" % "5.14.4"
    val junitEngine = "org.junit.jupiter" % "junit-jupiter-engine" % "5.14.4"
    val junitLauncher = "org.junit.platform" % "junit-platform-launcher" % "1.14.4"
    val testcontainers = "org.testcontainers" % "testcontainers" % "2.0.5"
    val jsonSchema = "com.networknt" % "json-schema-validator" % "2.0.8"
    val apacheClient = "software.amazon.awssdk" % "apache5-client" % "2.55.13"
    val nettyClient = "software.amazon.awssdk" % "netty-nio-client" % "2.55.13"
  }

  object logback {
    val classic = "ch.qos.logback" % "logback-classic" % logbackVersion
  }

  object scalatest {
    val scalatest = "org.scalatest" %% "scalatest" % scalaTest32Version
  }

}
