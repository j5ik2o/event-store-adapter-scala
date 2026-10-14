package com.github.j5ik2o.event.store.adapter.scala.apisurface

import org.scalatest.freespec.AnyFreeSpec

final class ApiSurfaceSpec extends AnyFreeSpec {
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
