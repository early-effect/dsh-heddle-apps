package earlyeffect.dsh.apps

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Try
import zio.json.ast.Json
import zio.test.*

object ContractSpec extends ZIOSpecDefault:
  def spec = suite("dsh-heddle-apps contract")(
    test("a short ascii name is mcp__server__tool"):
      assertTrue(PublicName.of("counter", "inc")(_ => "unused") == "mcp__counter__inc")
    ,
    test("a character outside the public alphabet is replaced and hashed"):
      val seen = AtomicReference("")
      val name = PublicName.of("counter", "show counter") { raw =>
        seen.set(raw)
        "fd7c29d494ac"
      }
      assertTrue(
        name == "mcp__counter__show_counter_fd7c29d494ac",
        seen.get == "counter\u0000show counter",
      )
    ,
    test("a name past 64 characters keeps a 12-hex suffix"):
      val name = PublicName.of("counter", "a" * 80)(_ => "f741f20ec1e8")
      assertTrue(name == "mcp__counter__aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_f741f20ec1e8", name.length == 64)
    ,
    test("mount meta round-trips, and a value without it does not"):
      val ref  = MountRef("counter", "ui://early-effect/counter", "call-1")
      val meta = Presentation.project(ref)
      val bare = Json.Obj("content" -> Json.Str("hi"))
      assertTrue(
        Presentation.read(meta) == Right(ref),
        Presentation.read(bare) == Left(MetaError.NoHeddle),
        Presentation.readValue(Presentation.value(ref, Json.Obj())) == Right((ref, Json.Obj())),
        Presentation.read(Presentation.project(ref.copy(title = Some("Counter")))) == Right(
          ref.copy(title = Some("Counter"))
        ),
      )
    ,
    test("only a ui:// link is claimed"):
      assertTrue(
        Claim.of(Some("ui://early-effect/counter")) == Claim.Linked("ui://early-effect/counter"),
        Claim.of(None) == Claim.Plain,
        Claim.of(Some("https://example.test")) == Claim.Plain,
        Claim.of(Some("ui://")) == Claim.Plain,
      )
    ,
    test("pins live under DSH_HOME, else under the user home"):
      assertTrue(
        PinsPath.from(Some("/var/dsh"), Some("/Users/ada")) == Some("/var/dsh/heddle-apps/pins.json"),
        PinsPath.from(Some(""), Some("/Users/ada")) == Some("/Users/ada/.dsh/heddle-apps/pins.json"),
        PinsPath.from(None, None).isEmpty,
      )
    ,
    test("plugin sources do not name a board"):
      val root = Iterator
        .iterate(Paths.get("").toAbsolutePath)(path => Option(path.getParent).getOrElse(path))
        .take(8)
        .find(path => Files.isRegularFile(path.resolve("plugin/config.js")))
      val hits = root.fold(List("plugin/config.js was not found from the working directory"))(SourceScan.hits)
      assertTrue(hits.isEmpty)
    ,
    test("the host contribution names the methods the web row mounts"):
      val invocations = arrayField(Descriptors.host, "invocations")
      val parameters  = arrayField(Descriptors.open, "parameters")
      assertTrue(
        textField(Descriptors.host, "package").contains(Descriptors.packageName),
        invocations.exists(_.length == 3),
        textField(Descriptors.names, "method").contains("names"),
        textField(Descriptors.status, "method").contains("status"),
        textField(Descriptors.open, "mode").contains("stream"),
        parameters.exists(_.exists(param => textField(param, "name").contains("callId"))),
      )
    ,
    test("config accepts http and stdio, and refuses a server name mcp-client would"):
      val ok = PluginConfig.parse(
        """{"servers":[
          |{"name":"counter","transport":"http","url":"http://127.0.0.1:9/mcp"},
          |{"name":"local","transport":"stdio","command":"node","args":["srv.js"]},
          |{"name":"boxed","transport":"stdio","command":"java","cwd":"/tmp/counter"}
          |]}""".stripMargin
      )
      val bad = PluginConfig.parse("""{"servers":[{"name":"has space","transport":"http","url":"http://x"}]}""")
      val bare = PluginConfig.parse("""{"servers":[{"name":"local","transport":"stdio","command":"java","cwd":""}]}""")
      assertTrue(
        ok == Right(
          zio.Chunk(
            Endpoint.Http("counter", "http://127.0.0.1:9/mcp"),
            Endpoint.Stdio("local", "node", zio.Chunk("srv.js")),
            Endpoint.Stdio("boxed", "java", zio.Chunk.empty, Some("/tmp/counter")),
          )
        ),
        bad == Left(ConfigError.Row(0, RowError.Name(NameError.BadCharacter(' ', 3)))),
        bare == Left(ConfigError.Row(0, RowError.Cwd)),
      )
    ,
  )

  private def textField(json: Json, name: String): Option[String] =
    json match
      case obj: Json.Obj => obj.fields.collectFirst { case (`name`, Json.Str(value)) => value }
      case _              => None

  private def arrayField(json: Json, name: String): Option[zio.Chunk[Json]] =
    json match
      case obj: Json.Obj => obj.fields.collectFirst { case (`name`, Json.Arr(items)) => items }
      case _              => None

  /** Reads the checkout. The needle is split so this file does not itself match. */
  private object SourceScan:
    private val needle = "to" + "do"
    private val roots = List(
      "core/src",
      "client/src",
      "host/src",
      "facade/src",
      "plugin/src",
      "counter/src",
      "plugin/config.js",
      "plugin/entry.js",
      "plugin/package.json",
      "README.md",
    )
    private val suffixes = Set(".scala", ".js", ".md", ".yml", ".yaml", ".json")

    def hits(root: Path): List[String] =
      roots.flatMap { rel =>
        Try(entries(root.resolve(rel))).fold(
          err => List(s"$rel: ${err.getClass.getSimpleName}"),
          paths => paths.flatMap(file => lineHits(root, file)),
        )
      }

    private def entries(path: Path): List[Path] =
      if !Files.exists(path) then Nil
      else if Files.isRegularFile(path) then List(path).filter(wanted)
      else
        val listed = Files.list(path)
        val children =
          try listed.iterator().asScala.toList
          finally listed.close()
        children.flatMap(entries)

    private def wanted(path: Path): Boolean =
      val name = path.getFileName.toString
      suffixes.exists(name.endsWith) &&
        !path.iterator().asScala.exists(part => part.toString == "target" || part.toString == "node_modules")

    private def lineHits(root: Path, file: Path): List[String] =
      Try(Files.readString(file, StandardCharsets.UTF_8)) match
        case scala.util.Success(text) =>
          text.linesIterator.zipWithIndex.collect {
            case (line, index) if line.contains(needle) => s"${root.relativize(file)}:${index + 1}"
          }.toList
        case scala.util.Failure(err) => List(s"${root.relativize(file)}: ${err.getClass.getSimpleName}")
  end SourceScan
end ContractSpec
