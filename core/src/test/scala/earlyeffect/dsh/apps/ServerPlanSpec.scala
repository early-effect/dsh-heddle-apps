package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ServerPlanSpec extends ZIOSpecDefault:
  private val counter = Endpoint.Http("counter", "http://127.0.0.1:9/mcp")
  private val local   = Endpoint.Stdio("local", "node", Chunk("srv.js"))

  def spec = suite("servers page")(
    test("the summary is No servers, or a count and how many are connected") {
      assertTrue(
        ServerPlan.summary(0, 0) == "No servers",
        ServerPlan.summary(2, 1) == "2 servers, 1 connected",
        ServerPlan.summary(1, 1) == "1 server, 1 connected",
      )
    },
    test("replace keeps an unchanged server, stops a removed one, and starts a new one") {
      val steps = ServerPlan.steps(Chunk(counter, local), Chunk(counter))
      assertTrue(
        steps == Chunk(ServerPlan.Step.Stop("local"), ServerPlan.Step.Keep(counter))
      )
    },
    test("a changed url stops the old session and starts the new target") {
      val moved = Endpoint.Http("counter", "http://127.0.0.1:10/mcp")
      assertTrue(
        ServerPlan.steps(Chunk(counter), Chunk(moved)) == Chunk(
          ServerPlan.Step.Stop("counter"),
          ServerPlan.Step.Start(moved),
        )
      )
    },
    test("a failed add is a start beside the servers that stay") {
      val added = Endpoint.Http("other", "http://127.0.0.1:11/mcp")
      assertTrue(
        ServerPlan.steps(Chunk(counter), Chunk(counter, added)) == Chunk(
          ServerPlan.Step.Keep(counter),
          ServerPlan.Step.Start(added),
        )
      )
    },
    test("switching transport keeps the hidden draft") {
      val http  = ServerDraft("counter", "http", ServerDraft.exampleUrl, "node", Chunk("srv.js"), "/tmp")
      val stdio = http.show("stdio")
      assertTrue(
        stdio.url == ServerDraft.exampleUrl,
        stdio.command == "node",
        stdio.shown == "stdio",
        stdio.show("http").command == "node",
      )
    },
    test("a blank url and a bad name are the row errors") {
      val blankUrl = ServerDraft("counter", "http", "", "", Chunk.empty, "")
      val blank    = PluginConfig.parse(Json.Obj("servers" -> Json.Arr(blankUrl.row)).toJson)
      val named    = ServerDraft("has space", "http", "http://127.0.0.1:9/mcp", "", Chunk.empty, "")
      val bad      = PluginConfig.parse(Json.Obj("servers" -> Json.Arr(named.row)).toJson)
      assertTrue(
        blank == Left(ConfigError.Row(0, RowError.Url)),
        bad == Left(ConfigError.Row(0, RowError.Name(NameError.BadCharacter(' ', 3)))),
      )
    },
    test("stdio args that are not strings, and an empty directory, are the row errors") {
      val args = Json.Obj(
        "name"      -> Json.Str("weather"),
        "transport" -> Json.Str("stdio"),
        "command"   -> Json.Str("node"),
        "args"      -> Json.Arr(Json.Num(java.math.BigDecimal.ONE)),
      )
      val cwd = Json.Obj(
        "name"      -> Json.Str("weather"),
        "transport" -> Json.Str("stdio"),
        "command"   -> Json.Str("node"),
        "cwd"       -> Json.Str(""),
      )
      assertTrue(
        PluginConfig.parse(Json.Obj("servers" -> Json.Arr(args)).toJson) ==
          Left(ConfigError.Row(0, RowError.Args)),
        PluginConfig.parse(Json.Obj("servers" -> Json.Arr(cwd)).toJson) ==
          Left(ConfigError.Row(0, RowError.Cwd)),
      )
    },
    test("a tool line is the summary that server sent") {
      assertTrue(
        ToolCopy.line(Some("Forecast"), Some("ignored"), "forecast") == "Forecast",
        ToolCopy.line(Some("  "), Some("Forecast"), "forecast") == "Forecast",
        ToolCopy.line(None, None, "forecast") == "forecast",
      )
    },
    test("the consent question is the summary and the server, and the arguments are labeled") {
      val lines =
        ConsentCopy.lines(Json.Obj("text" -> Json.Str("buy milk"), "n" -> Json.Num(new java.math.BigDecimal(2))))
      assertTrue(
        ConsentCopy.question(Some("Increment"), "inc", "counter") == "Increment on counter?",
        ConsentCopy.question(None, "inc", "counter") == "inc on counter?",
        lines == Chunk("text: buy milk", "n: 2"),
        ConsentCopy.denied == "Not allowed.",
        !lines.exists(_.contains("{")),
      )
    },
    test("a card says connecting, the tool count, or that an edit is not live") {
      assertTrue(
        RowReport.status(Phase.Connecting) == "Connecting.",
        RowReport.status(Phase.Down("timed out")) == "Can't reach the server. timed out",
        RowReport.status(Phase.Up(Chunk.empty, Some("Asks for no network."))) == "Connected. No apps to open.",
        RowReport.status(Phase.Up(Chunk("Increment"), None)) == "Connected, 1 tool.",
        RowReport.status(Phase.Up(Chunk("Increment", "Reset"), None)) == "Connected, 2 tools.",
        RowReport.edited("Connected, 2 tools.") == "Not live yet. Connected, 2 tools.",
        RowReport.removeQuestion("counter") == "Remove counter? Its tools leave the chat.",
        GrantCopy.sentence(Set.empty) == "Asks for no network.",
        GrantCopy.sentence(Set("https://b.test", "https://a.test")) == "May connect to https://a.test, https://b.test.",
      )
    },
    test("a status document round-trips, and a value that is not a list does not") {
      val rows = Chunk(
        RowReport("counter", Phase.Up(Chunk("Increment"), Some("Asks for no network."))),
        RowReport("local", Phase.Down("connection refused")),
      )
      assertTrue(
        RowReport.read(RowReport.document(rows).toJson) == Right(rows),
        RowReport.read("[]") == Right(Chunk.empty),
        RowReport.read("{}") == Left(ReportError.NotArray),
      )
    },
  )
end ServerPlanSpec
