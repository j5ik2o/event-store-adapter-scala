package com.github.j5ik2o.event.store.adapter.scala.apisurface

import org.scalatest.freespec.AnyFreeSpec
import com.github.j5ik2o.event.store.adapter.scala.{EventStore, EventStoreAsync}

final class ApiSurfaceSpec extends AnyFreeSpec {
  "legacy storage factories retain their JVM Deprecated annotations" in {
    val names = Set("ofDynamoDB", "ofSpanner", "ofBigtable", "ofSqlite")
    Seq(EventStore.getClass, EventStoreAsync.getClass).foreach { companion =>
      val factories = companion.getDeclaredMethods.filter(method => names.contains(method.getName))
      assert(factories.map(_.getName).toSet == names)
      assert(factories.forall(_.isAnnotationPresent(classOf[Deprecated])))
    }
  }

  "the current public API" - {
    "has the surface version that scala.json was written for" in {
      val surface = ApiSurfaceFiles.read(ApiSurfaceFiles.surfacePath)
      val mapping = ApiSurfaceFiles.read(ApiSurfaceFiles.mappingPath)
      assert(surface.path("version").asText() == mapping.path("surface_version").asText())
    }

    "matches the API surface without any deviation" in
      assert(ApiSurfaceChecker.load().check() == Seq.empty[String])
  }
}
