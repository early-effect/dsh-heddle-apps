package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.ast.Json

/** The host Typert contribution: `names` so a web row can claim slots before a call, and `open` so one mounted view
  * has one stream. Codecs are `src-json`. The web row mounts the same methods with strict codecs, which is what
  * `$mount` accepts.
  */
object Descriptors:
  val packageName: String = "@early-effect/dsh-heddle-apps"
  val service: String     = "heddle-apps"

  val srcJson: Json = Json.Obj("mode" -> Json.Str("src-json"))

  val names: Json = method(
    id = "heddle-apps#names",
    method = "names",
    parameters = Chunk.empty,
    stream = false,
    uplink = false,
  )

  val status: Json = method(
    id = "heddle-apps#status",
    method = "status",
    parameters = Chunk.empty,
    stream = false,
    uplink = false,
  )

  val open: Json = method(
    id = "heddle-apps#open",
    method = "open",
    parameters = Chunk(parameter("callId")),
    stream = true,
    uplink = true,
  )

  /** What `ctx.typert.register` takes on the host. */
  val host: Json = Json.Obj(
    "package"     -> Json.Str(packageName),
    "face"        -> Json.Str("host"),
    "schemas"     -> Json.Arr(Chunk.empty),
    "model"       -> Json.Obj(
      "services" -> Json.Arr(Chunk.empty),
      "events"   -> Json.Arr(Chunk.empty),
      "objects"  -> Json.Arr(Chunk.empty),
    ),
    "invocations" -> Json.Arr(Chunk(names, open, status)),
  )

  private def method(
      id: String,
      method: String,
      parameters: Chunk[Json],
      stream: Boolean,
      uplink: Boolean,
  ): Json =
    val fields = Chunk(
      "id"         -> Json.Str(id),
      "service"    -> Json.Str(service),
      "namespace"  -> Json.Str(service),
      "method"     -> Json.Str(method),
      "invocation" -> Json.Obj("kind" -> Json.Str("direct")),
      "parameters" -> Json.Arr(parameters),
      "result"     -> srcJson,
    ) ++ Chunk.from(Option.when(stream)("mode" -> Json.Str("stream"))) ++
      Chunk.from(Option.when(uplink)("uplink" -> Json.Obj("codec" -> srcJson)))
    Json.Obj(fields)

  private def parameter(name: String): Json =
    Json.Obj(
      "name"   -> Json.Str(name),
      "wire"   -> Json.Str(name),
      "source" -> Json.Str("json"),
      "codec"  -> srcJson,
    )
end Descriptors
