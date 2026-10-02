package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Why a status document could not be read. */
enum ReportError:
  case NotArray
  case Row

/** What the host has learned about one saved server. The page polls this. It does not guess a connection. */
enum Phase:
  case Connecting
  case Down(message: String)
  case Up(tools: Chunk[String], grant: Option[String])

final case class RowReport(name: String, phase: Phase)

object RowReport:
  def status(phase: Phase): String =
    phase match
      case Phase.Connecting              => "Connecting."
      case Phase.Down(message)           => s"Can't reach the server. $message"
      case Phase.Up(tools, _) if tools.isEmpty => "Connected. No apps to open."
      case Phase.Up(tools, _) if tools.length == 1 => "Connected, 1 tool."
      case Phase.Up(tools, _)            => s"Connected, ${tools.length} tools."

  /** An unsaved edit keeps the last live sentence and says it is not what is running. */
  def edited(live: String): String = s"Not live yet. $live"

  def removeQuestion(name: String): String = s"Remove $name? Its tools leave the chat."

  def document(rows: Chunk[RowReport]): Json = Json.Arr(rows.map(wire))

  def read(text: String): Either[ReportError, Chunk[RowReport]] =
    text.fromJson[Json] match
      case Right(Json.Arr(items)) =>
        items.foldLeft[Either[ReportError, Chunk[RowReport]]](Right(Chunk.empty)) {
          case (Left(err), _)    => Left(err)
          case (Right(acc), item) => one(item).map(acc :+ _)
        }
      case _ => Left(ReportError.NotArray)

  private def wire(report: RowReport): Json =
    val (phase, detail, tools, grant) = report.phase match
      case Phase.Connecting => ("connecting", "", Chunk.empty[String], None)
      case Phase.Down(message) => ("down", message, Chunk.empty[String], None)
      case Phase.Up(tools, grant) => ("up", "", tools, grant)
    val fields = Chunk(
      "name"   -> Json.Str(report.name),
      "phase"  -> Json.Str(phase),
      "detail" -> Json.Str(detail),
      "tools"  -> Json.Arr(tools.map(Json.Str(_))),
    ) ++ grant.map(text => "grant" -> Json.Str(text))
    Json.Obj(fields)

  private def one(json: Json): Either[ReportError, RowReport] =
    json match
      case obj: Json.Obj =>
        val name = text(obj, "name")
        val phase = text(obj, "phase")
        (name, phase) match
          case (Some(name), Some("connecting")) => Right(RowReport(name, Phase.Connecting))
          case (Some(name), Some("down")) =>
            Right(RowReport(name, Phase.Down(text(obj, "detail").getOrElse(""))))
          case (Some(name), Some("up")) =>
            Right(RowReport(name, Phase.Up(strings(obj, "tools"), text(obj, "grant"))))
          case _ => Left(ReportError.Row)
      case _ => Left(ReportError.Row)

  private def text(obj: Json.Obj, field: String): Option[String] =
    obj.fields.collectFirst { case (`field`, Json.Str(value)) => value }

  private def strings(obj: Json.Obj, field: String): Chunk[String] =
    obj.fields.collectFirst { case (`field`, Json.Arr(items)) => items } match
      case Some(items) => items.collect { case Json.Str(value) => value }
      case None        => Chunk.empty
end RowReport
