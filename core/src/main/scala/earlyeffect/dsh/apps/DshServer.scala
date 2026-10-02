package earlyeffect.dsh.apps

/** A server name `dsh-mcp-client` would accept: 1 to 32 of `[A-Za-z0-9_-]`. */
enum NameError(val message: String):
  case Empty extends NameError("a server name is not empty")
  case TooLong(length: Int) extends NameError(s"a server name is at most ${DshServer.Max} characters, not $length")
  case BadCharacter(char: Char, at: Int)
      extends NameError(s"'$char' at $at: a server name is letters, digits, '_', and '-'")

object DshServer:
  val Max: Int = 32

  def from(raw: String): Either[NameError, String] =
    if raw.isEmpty then Left(NameError.Empty)
    else if raw.length > Max then Left(NameError.TooLong(raw.length))
    else
      raw.indexWhere(c => !allowed(c)) match
        case -1 => Right(raw)
        case at => Left(NameError.BadCharacter(raw(at), at))

  private def allowed(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
end DshServer
