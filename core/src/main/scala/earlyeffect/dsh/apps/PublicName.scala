package earlyeffect.dsh.apps

/** The model-facing name `dsh-mcp-client` would register for the same server and tool.
  *
  * mcp-client keeps its `serverName` reservation in a private map, so another plugin cannot ask whether a name is
  * taken. Registering this exact string is the collision `ctx.tools.register` can still reject.
  */
object PublicName:
  val Max: Int     = 64
  val HashLen: Int = 12

  /** `hash12` is the first 12 hex characters of SHA-256 over `server + NUL + raw`. */
  def of(server: String, raw: String)(hash12: String => String): String =
    val joined     = s"mcp__${server}__$raw"
    val normalized = joined.map(c => if allowed(c) then c else '_')
    if normalized == joined && normalized.length <= Max then normalized
    else
      val hash = hash12(s"$server\u0000$raw")
      val keep = Max - HashLen - 1
      s"${normalized.take(keep)}_$hash"

  private def allowed(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
end PublicName
