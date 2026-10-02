package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** The consent question. The action is the tool summary when the server published one. */
object ConsentCopy:
  val denied: String = "Not allowed."

  def question(summary: Option[String], tool: String, server: String): String =
    val action = summary.map(_.trim).filter(_.nonEmpty).getOrElse(tool)
    s"$action on $server?"

  /** One line per argument. The label is the field name. The value is not a JSON object. */
  def lines(arguments: Json.Obj): Chunk[String] =
    arguments.fields.map((name, value) => s"$name: ${shown(value)}")

  private def shown(value: Json): String =
    value match
      case Json.Str(text)  => text
      case Json.Num(n)     => n.toString
      case Json.Bool(b)    => b.toString
      case Json.Null       => ""
      case other           => other.toJson
end ConsentCopy
