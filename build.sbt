import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*

AppsVersions.settings

// Host and frame come from the published heddle snapshot. The counter example still reads the sibling
// checkout when that build is on disk. CI does not have it, so the counter project is empty there.
zipxCheckDeps := false

ThisBuild / scalaVersion := (AppsVersions.scala: String)
ThisBuild / organization := "rocks.earlyeffect"

val scalaVersions = Seq[String](AppsVersions.scala)

val commonScalacOptions = Seq("-deprecation", "-feature", "-Wunused:all")

val skipPublish = Seq(
  publish / skip  := true,
  publishArtifact := false,
)

val skipTests = Seq(
  Test / skip     := true,
  Test / sources  := Nil,
  Test / test     := Def.uncached(sbt.protocol.testing.TestResult.Passed),
  Test / testFull := Def.uncached(sbt.protocol.testing.TestResult.Passed),
)

val stagePlugin = taskKey[File]("Copy the host plugin fullLinkJS output to plugin/lib/index.js")
val stageClient = taskKey[File]("Wrap the web client fullLinkJS output as plugin/client.js")
val clientCheck = taskKey[Unit]("fail if the web client links node:fs, node:path, or node:process")

def wrapClientBundle(src: File, dest: File): Unit =
  val body = IO.read(src)
  val prefix =
    """window.__ModuleLoader__.load({
      |  id: '@early-effect/dsh-heddle-apps',
      |  factory: (require) => {
      |    const module = { exports: {} }
      |    const exports = module.exports
      |""".stripMargin
  val suffix =
    """
      |    return module.exports
      |  },
      |})
      |""".stripMargin
  IO.write(dest, prefix + body + suffix)
end wrapClientBundle

lazy val root = (project in file("."))
  .aggregate(
    LocalProject("core"),
    LocalProject("coreJS"),
    facade,
    host,
    webClient,
    heddlePlugin,
    counter,
  )
  .settings(
    name := "dsh-heddle-apps-root",
    skipPublish,
    Test / skip := true,
  )

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "dsh-heddle-apps-core",
    scalacOptions ++= commonScalacOptions,
    AppsVersions.zioLib,
    AppsVersions.jsonLib,
    AppsVersions.zioTests,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions)

lazy val facade = (project in file("facade"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "dsh-heddle-apps-facade",
    skipPublish,
    skipTests,
    scalacOptions ++= commonScalacOptions,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )

lazy val host = (project in file("host"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(
    LocalProject("coreJS"),
    facade,
  )
  .settings(
    name := "dsh-heddle-apps-host",
    skipPublish,
    skipTests,
    scalacOptions ++= commonScalacOptions,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    AppsVersions.hostLib,
  )

lazy val webClient = (project in file("client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(
    LocalProject("coreJS"),
    facade,
  )
  .settings(
    name := "dsh-heddle-apps-client",
    AppsVersions.frameLib,
    skipPublish,
    skipTests,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    clientCheck := Def.uncached {
      (Compile / fullLinkJS).value
      val out  = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      val code = IO.listFiles(out).filter(_.getName.endsWith(".js")).map(IO.read(_)).mkString("\n")
      val nodes = """(?:require\s*\(\s*|from\s+|import\s*\(?\s*)["'](node:fs|node:path|node:process)["']""".r
        .findAllMatchIn(code)
        .map(_.group(1))
        .toList
        .distinct
      if nodes.nonEmpty then sys.error(s"web client bundle imports Node modules: ${nodes.mkString(", ")}")
      streams.value.log.info(s"web client bundle is Node-free (${code.length / 1024} KiB)")
    },
    stageClient := Def.uncached {
      val _    = clientCheck.value
      val dest = (ThisBuild / baseDirectory).value / "plugin" / "client.js"
      val out  = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      wrapClientBundle(out / "main.js", dest)
      dest
    },
  )

lazy val heddlePlugin = (project in file("plugin"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(host)
  .settings(
    name := "dsh-heddle-apps",
    skipPublish,
    skipTests,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    stagePlugin := Def.uncached {
      val staged = (webClient / stageClient).value
      val dest   = (ThisBuild / baseDirectory).value / "plugin" / "lib"
      IO.createDirectory(dest)
      val out    = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      val linked = (Compile / fullLinkJS).value
      val js     = dest / "index.js"
      IO.copyFile(out / "main.js", js)
      locally(staged)
      locally(linked)
      js
    },
  )

lazy val counterClasspath = taskKey[File]("write counter/target/classpath for counter/stdio.sh")

lazy val counter = (project in file("counter"))
  .settings(
    name := "dsh-heddle-apps-counter",
    skipPublish,
    scalacOptions ++= commonScalacOptions,
    AppsVersions.zioTests,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / run / fork := true,
    counterClasspath := Def.uncached {
      val conv = fileConverter.value
      val cp   = (Compile / fullClasspath).value
        .map(entry => conv.toPath(entry.data).toAbsolutePath.toString)
        .mkString(java.io.File.pathSeparator)
      val dest = baseDirectory.value / "target" / "classpath"
      IO.write(dest, cp)
      dest
    },
  )
  .configure(counterHeddle)

/** The counter example compiles the sibling heddle checkout when it is on disk. CI has no checkout, so the sources
  * stay empty and this build does not ask sbt to open `../heddle`.
  *
  * `DSH_HEDDLE_CHECKOUT=0` forces that CI graph. `zipxWorkflowGenerate` must run that way: loading the sibling writes
  * heddle's publish tasks into this repo's workflow.
  */
def counterHeddle(project: Project): Project =
  val checkout =
    sys.env.get("DSH_HEDDLE_CHECKOUT") match
      case Some("0") => false
      case _         => file("../heddle/build.sbt").exists
  if checkout then
    val view = ProjectRef(file("../heddle"), "appsBrowserView")
    project
      .dependsOn(ProjectRef(file("../heddle"), "appsBrowserShared"))
      .settings(
        Compile / resourceGenerators += Def.task {
          val _    = (view / Compile / fastLinkJS).value
          val dest = (Compile / resourceManaged).value / "counter-view.js"
          IO.copyFile((view / Compile / fastLinkJSOutput).value / "main.js", dest)
          Seq(dest)
        }.taskValue,
      )
  else
    project.settings(
      Compile / sources := Nil,
      Test / sources    := Nil,
    )
