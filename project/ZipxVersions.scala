import zipx.*

object AppsVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson    = Lib("dev.zio", "zio-json", "1.1.0")

  val heddle      = Lib("rocks.earlyeffect", "heddle", "0.9.0-6d468f16f6ee-SNAPSHOT")
  val heddleHost  = heddle.mod("heddle-mcp-apps-host")
  val heddleFrame = heddle.mod("heddle-mcp-apps-frame")

  val scalajs  = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynverCi = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")

  def zioTests = library(zioTest.test, zioTestSbt.test)
  def hostLib  = library(heddleHost)
  def frameLib = library(heddleFrame)
  def zioLib   = library(zio)
  def jsonLib  = library(zioJson)
end AppsVersions
