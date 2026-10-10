package com.github.j5ik2o.event.store.adapter.scala.conformance

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.{LauncherDiscoveryRequestBuilder, LauncherFactory}
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.scalatest.freespec.AnyFreeSpec

import java.io.{PrintWriter, StringWriter}

final class ScalaPublicConformanceSpec extends AnyFreeSpec {
  "all applicable distributed cases through Scala public factories and operations" in {
    val listener = new SummaryGeneratingListener()
    val request = LauncherDiscoveryRequestBuilder
      .request()
      .selectors(selectClass("com.github.j5ik2o.event.store.adapter.java.conformance.ConformanceRunnerTest"))
      .build()
    LauncherFactory.create().execute(request, listener)
    val summary = listener.getSummary
    val diagnostics = new StringWriter()
    summary.printFailuresTo(new PrintWriter(diagnostics))
    assert(summary.getTestsStartedCount > 0, "The conformance runner must execute tests")
    assert(summary.getTotalFailureCount == 0, diagnostics.toString)
    assert(summary.getTestsSkippedCount == 0)
    assert(summary.getTestsAbortedCount == 0)
  }
}
