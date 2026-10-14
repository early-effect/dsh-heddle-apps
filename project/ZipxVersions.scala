import zipx.*

object AppsVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-RC1")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson    = Lib("dev.zio", "zio-json", "1.1.0")

  val heddle      = Lib("rocks.earlyeffect", "heddle", "0.11.0")
  val heddleHost  = heddle.mod("heddle-mcp-apps-host")
  val heddleFrame = heddle.mod("heddle-mcp-apps-frame")

  val scalajs  = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynverCi = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")

  /** Docs only. This repo is not a library, and these rows are not an install coordinate. */
  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.20.0")
  val specularZioTest = specular.mod("specular-zio-test").test
  val specularSite    = specular.mod("specular-site")
  val specularTheme   = specular.mod("early-effect-docs-theme")
  val mermoid         = Lib("rocks.earlyeffect", "mermoid", "0.2.0")
  val specularPlugin  = Plugin("rocks.earlyeffect", "sbt-specular", "0.20.0")

  def zioTests = library(zioTest.test, zioTestSbt.test)
  def hostLib  = library(heddleHost)
  def frameLib = library(heddleFrame)
  def zioLib   = library(zio)
  def jsonLib  = library(zioJson)
  def docsLib  = library(specular, specularSite, specularTheme, mermoid, specularZioTest)
end AppsVersions
