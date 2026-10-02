import zipx.*

object AppsVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson    = Lib("dev.zio", "zio-json", "1.1.0")

  val scalajs  = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynverCi = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")

  def zioTests = library(zioTest.test, zioTestSbt.test)
  def zioLib   = library(zio)
  def jsonLib  = library(zioJson)
end AppsVersions
