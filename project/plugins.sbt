addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.2")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

// Do not re-add sbt-site or sbt-ghpages. sbt-ghpages pulls sbt-git 0.9.3, whose
// JGit status runs at project load and fails in git linked worktrees because the
// resolved JGit cannot read commondir. sbt-dynver derives the version from tags.
