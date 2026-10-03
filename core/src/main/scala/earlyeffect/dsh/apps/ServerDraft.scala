package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.ast.Json

/** One card. Switching transport shows the other fields and keeps the hidden ones until Save. */
final case class ServerDraft(
    name: String,
    shown: String,
    url: String,
    command: String,
    args: Chunk[String],
    cwd: String,
    fresh: Boolean = false,
):
  def show(transport: String): ServerDraft = copy(shown = transport)

  def row: Json =
    if shown == "stdio" then
      val fields = Chunk(
        "name"      -> Json.Str(name),
        "transport" -> Json.Str("stdio"),
        "command"   -> Json.Str(command),
        "args"      -> Json.Arr(args.map(Json.Str(_))),
      ) ++ (if cwd.isEmpty then Chunk.empty else Chunk("cwd" -> Json.Str(cwd)))
      Json.Obj(fields)
    else Json.Obj("name" -> Json.Str(name), "transport" -> Json.Str("http"), "url" -> Json.Str(url))
end ServerDraft

object ServerDraft:
  val exampleUrl: String = "http://127.0.0.1:8080/mcp"

  val blank: ServerDraft = ServerDraft("", "http", "", "", Chunk.empty, "", fresh = true)

  def from(endpoint: Endpoint): ServerDraft =
    endpoint match
      case Endpoint.Http(name, url)                 => ServerDraft(name, "http", url, "", Chunk.empty, "")
      case Endpoint.Stdio(name, command, args, cwd) =>
        ServerDraft(name, "stdio", "", command, args, cwd.getOrElse(""))
end ServerDraft
