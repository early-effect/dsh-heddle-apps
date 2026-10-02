package earlyeffect.dsh.apps.counter

import heddle.mcp.protocol.{ProtocolVersion, RequestMeta}
import zio.*
import zio.json.ast.Json
import zio.test.*

object CounterAppSpec extends ZIOSpecDefault:
  def spec = suite("counter app")(
    test("show_counter is for the model and inc is for the view"):
      for
        app <- CounterApp.open("/* view */")
        answer <- app.handle(listTools).someOrFail(CounterBoot.Pipe("no answer"))
      yield
        val tools = listed(answer)
        assertTrue(
          tools.exists(tool => nameOf(tool).contains("show_counter") && visibility(tool).contains("model")),
          tools.exists(tool =>
            nameOf(tool).contains("inc") && visibility(tool).contains("app") && !visibility(tool).contains("model")
          ),
          tools.exists(tool => nameOf(tool).contains("show_counter") && resourceOf(tool).contains("ui://counter/view")),
        )
  )

  private def listTools: Json.Obj =
    val meta = Json.Obj(
      RequestMeta.VersionKey -> Json.Str(ProtocolVersion.Current.value),
      RequestMeta.ClientCapsKey -> Json.Obj(),
    )
    Json.Obj(
      "jsonrpc" -> Json.Str("2.0"),
      "id" -> Json.Num(1),
      "method" -> Json.Str("tools/list"),
      "params" -> Json.Obj("_meta" -> meta),
    )

  private def listed(json: Json): Chunk[Json.Obj] =
    json match
      case obj: Json.Obj =>
        obj.fields.collectFirst { case ("result", result: Json.Obj) => result } match
          case Some(result) =>
            result.fields.collectFirst { case ("tools", Json.Arr(items)) =>
              items.collect { case tool: Json.Obj => tool }
            }.getOrElse(Chunk.empty)
          case None => Chunk.empty
      case _ => Chunk.empty

  private def nameOf(tool: Json.Obj): Option[String] =
    tool.fields.collectFirst { case ("name", Json.Str(name)) => name }

  private def visibility(tool: Json.Obj): Chunk[String] =
    ui(tool).flatMap(_.fields.collectFirst { case ("visibility", Json.Arr(items)) =>
      items.collect { case Json.Str(who) => who }
    }).getOrElse(Chunk.empty)

  private def resourceOf(tool: Json.Obj): Option[String] =
    ui(tool).flatMap(_.fields.collectFirst { case ("resourceUri", Json.Str(uri)) => uri })

  private def ui(tool: Json.Obj): Option[Json.Obj] =
    tool.fields
      .collectFirst { case ("_meta", meta: Json.Obj) => meta }
      .flatMap(_.fields.collectFirst { case ("ui", body: Json.Obj) => body })
end CounterAppSpec
