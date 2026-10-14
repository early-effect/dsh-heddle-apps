package earlyeffect.dsh.apps.docs

import specular.{CiteFormat, CiteResolver, DocNode, Section, SourceCite}
import specular.site.ProjectMeta
import specular.ziotest.DocSpecSuite
import zio.*
import zio.test.*

import java.nio.file.Files

object SequenceSuite extends DocSpecSuite:

  def doc = Sequence.doc

  override def spec =
    suite("Sequence")(
      super.spec,
      test("the citation is PluginConfig.parse") {
        cites(doc.children) match
          case Vector(head) =>
            CiteResolver.resolve(head, CiteFormat.AsWritten).map { source =>
              assertTrue(
                source.path.endsWith("PluginConfig.scala"),
                source.text.contains("def parse"),
                source.startLine >= 1,
                source.endLine >= source.startLine,
              )
            }
          case other =>
            ZIO.succeed(assertTrue(other.length == 1))
      },
      test("an unpublished meta has no version badge") {
        val raw = ProjectMeta(
          name = "dsh-heddle-apps",
          organization = "rocks.earlyeffect",
          version = "0.0.0+4-a1b2c3d4",
          scalaVersion = "3.9.0",
          displayVersion = Some("0.0.0+4-a1b2c3d4"),
        )
        val shown = BuildSite.unpublished(raw)
        assertTrue(
          shown.versionBadge.isEmpty,
          shown.docsVersion.isEmpty,
          !shown.sbtDependency().contains("0.0.0+4"),
          !shown.sbtDependency().contains("a1b2c3d4"),
        )
      },
      test("the built index is the sequence page and names no coordinate") {
        for
          tmp <- ZIO.attempt(Files.createTempDirectory("dsh-heddle-apps-docs"))
          props = Map(
            "specular.meta.name"           -> "dsh-heddle-apps",
            "specular.meta.organization"   -> "rocks.earlyeffect",
            "specular.meta.version"        -> "0.0.0+4-a1b2c3d4",
            "specular.meta.scalaVersion"   -> "3.9.0",
            "specular.meta.displayVersion" -> "0.0.0+4-a1b2c3d4",
            "specular.meta.description"    -> "A dsh plugin that hosts MCP Apps.",
            "specular.cite.sourceBase"     -> "https://github.com/early-effect/dsh-heddle-apps/blob/0123456789abcdef",
            "specular.site.dir"            -> tmp.toString,
          )
          _     <- ZIO.withConfigProvider(ConfigProvider.fromMap(props))(BuildSite.build)
          index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
          front <- ZIO.attempt(Files.readString(tmp.resolve("sequence.html")))
          meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
        yield assertTrue(
          index == front,
          index.contains("Plugin"),
          index.contains("HTTP or stdio"),
          index.contains("Frame"),
          index.contains("callTool"),
          index.contains("listTools"),
          index.contains("def parse"),
          index.contains("PluginConfig.scala#L"),
          index.contains("github.com/early-effect/dsh-heddle-apps/blob/0123456789abcdef/"),
          index.contains("heddlePlugin/stagePlugin"),
          !index.contains("libraryDependencies"),
          !index.contains("addSbtPlugin"),
          !index.contains("0.0.0+4"),
          !index.contains("a1b2c3d4"),
          !index.contains("SNAPSHOT"),
          !index.contains(" · v"),
          !meta.contains("0.0.0+4"),
          !meta.contains("a1b2c3d4"),
          !meta.contains("SNAPSHOT"),
        )
      },
    )

  private def cites(nodes: Vector[DocNode]): Vector[SourceCite] =
    nodes.flatMap {
      case cite: SourceCite     => Vector(cite)
      case Section(_, children) => cites(children)
      case _                    => Vector.empty
    }
end SequenceSuite
