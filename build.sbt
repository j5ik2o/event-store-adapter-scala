import Dependencies.{ fasterxml, j5ik2o, logback, scalatest, Versions }
import Dependencies.Versions._

ThisBuild / organization := "io.github.j5ik2o"
ThisBuild / organizationName := "io.github.j5ik2o"
ThisBuild / homepage := Some(url("https://github.com/j5ik2o/event-store-adapter-scala"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer(
    id = "j5ik2o",
    name = "Junichi Kato",
    email = "j5ik2o@gmail.com",
    url = url("https://blog.j5ik2o.me")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/j5ik2o/event-store-adapter-scala"),
    "scm:git@github.com:j5ik2o/event-store-adapter-scala.git"
  )
)
ThisBuild / scalaVersion := Versions.scala213Version
ThisBuild / crossScalaVersions := Seq(
  Versions.scala213Version,
  Versions.scala3Version
)
val commonFlags = Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-encoding",
  "UTF-8"
)
def extraFlags(scalaVer: String): Seq[String] =
  CrossVersion.partialVersion(scalaVer) match {
    case Some((3, _)) =>
      Seq(
        "-source:3.0-migration",
        "-Xignore-scala2-macros",
        "-Xtarget:8",
        "-Wunused:all"
      )
    case Some((2, _)) =>
      Seq(
        "-language:_",
        "-Ydelambdafy:method",
        "-target:jvm-1.8",
        "-Yrangepos",
        "-Ywarn-unused"
      )
    case _ => Nil
  }
ThisBuild / scalacOptions ++= commonFlags ++ extraFlags(scalaVersion.value)
ThisBuild / resolvers ++= Seq(
  "Seasar Repository" at "https://maven.seasar.org/maven2/",
  "DynamoDB Local Repository" at "https://s3-us-west-2.amazonaws.com/dynamodb-local/release"
)
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / Test / publishArtifact := false
ThisBuild / Test / fork := true
ThisBuild / Test / parallelExecution := false
ThisBuild / Compile / doc / sources := {
  val old = (Compile / doc / sources).value
  if (scalaVersion.value == scala3Version) {
    Nil
  } else {
    old
  }
}
ThisBuild / envVars := Map(
  "AWS_REGION"                                   -> "ap-northeast-1",
  "AWS_JAVA_V1_DISABLE_DEPRECATION_ANNOUNCEMENT" -> "true"
)
ThisBuild / scalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)
ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / dynverSeparator := "-"
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := (_ => false)
ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / "1.0" / "sonatype_credentials")
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("staging")
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("releases")
ThisBuild / resolvers += "Seasar Repository" at "https://maven.seasar.org/maven2/"

lazy val root = (project in file("."))
  .settings(
    name := "event-store-adapter-scala",
    libraryDependencies ++= Seq(
      scalatest.scalatest                % Test,
      logback.classic                    % Test,
      j5ik2o.dockerController_ScalaTest  % Test,
      j5ik2o.dockerController_LocalStack % Test,
      j5ik2o.eventStoreAdapterJava,
      fasterxml.jacksonModuleScala
    )
  )

// --- Custom commands
addCommandAlias("lint", ";scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;scalafixAll --check")
addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt;scalafix RemoveUnused")
