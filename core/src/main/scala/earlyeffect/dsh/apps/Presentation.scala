package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.ast.Json

/** The mount identity a tool result carries in `meta.heddle`, and the canonical value the tool returns so that
  * projection stays a pure function of the value.
  */
final case class MountRef(server: String, ui: String, callId: String, title: Option[String] = None)

enum MetaError(val message: String):
  case NotObject           extends MetaError("mount meta is an object")
  case NoHeddle            extends MetaError("mount meta has no heddle object")
  case Field(name: String) extends MetaError(s"heddle.$name is a non-empty string")
  case Result              extends MetaError("the tool value has no result")

object Presentation:
  def project(ref: MountRef): Json =
    Json.Obj("heddle" -> Json.Obj(identity(ref)))

  def read(json: Json): Either[MetaError, MountRef] =
    json match
      case obj: Json.Obj =>
        obj.fields.collectFirst { case ("heddle", body: Json.Obj) => body } match
          case None       => Left(MetaError.NoHeddle)
          case Some(body) => refOf(body)
      case _ => Left(MetaError.NotObject)

  /** What `execute` returns. `presentationMeta` reads [[project]] from it; `render` shows only `result`. */
  def value(ref: MountRef, result: Json): Json =
    Json.Obj(identity(ref) :+ ("result" -> result))

  def readValue(json: Json): Either[MetaError, (MountRef, Json)] =
    json match
      case obj: Json.Obj =>
        for
          ref    <- refOf(obj)
          result <- obj.fields.collectFirst { case ("result", value) => value }.toRight(MetaError.Result)
        yield (ref, result)
      case _ => Left(MetaError.NotObject)

  /** JSON Schema for [[value]]. `result` is the server's own JSON, unchecked here. */
  val schema: Json =
    Json.Obj(
      "type"                 -> Json.Str("object"),
      "additionalProperties" -> Json.Bool(false),
      "required"             -> Json.Arr(Chunk("server", "ui", "callId", "result").map(Json.Str(_))),
      "properties"           -> Json.Obj(
        "server" -> Json.Obj("type" -> Json.Str("string")),
        "ui"     -> Json.Obj("type" -> Json.Str("string")),
        "callId" -> Json.Obj("type" -> Json.Str("string")),
        "title"  -> Json.Obj("type" -> Json.Str("string")),
        "result" -> Json.Obj(),
      ),
    )

  private def refOf(obj: Json.Obj): Either[MetaError, MountRef] =
    for
      server <- text(obj, "server")
      ui     <- text(obj, "ui")
      callId <- text(obj, "callId")
    yield MountRef(server, ui, callId, optional(obj, "title"))

  private def identity(ref: MountRef): Chunk[(String, Json)] =
    Chunk(
      "server" -> Json.Str(ref.server),
      "ui"     -> Json.Str(ref.ui),
      "callId" -> Json.Str(ref.callId),
    ) ++ ref.title.filter(_.nonEmpty).map(text => "title" -> Json.Str(text))

  private def optional(obj: Json.Obj, name: String): Option[String] =
    obj.fields.collectFirst { case (`name`, Json.Str(s)) if s.nonEmpty => s }

  private def text(obj: Json.Obj, name: String): Either[MetaError, String] =
    obj.fields.collectFirst { case (`name`, Json.Str(s)) if s.nonEmpty => s }.toRight(MetaError.Field(name))
end Presentation
