package earlyeffect.dsh.apps

/** Whether a listed tool is an App launch. MIME, the result schema, and the hash pin are the host kit's mount, which
  * runs only after this says the tool is linked.
  */
enum Claim:
  case Linked(ui: String)
  case Plain

object Claim:
  def of(resourceUri: Option[String]): Claim =
    resourceUri match
      case Some(uri) if ui(uri) => Claim.Linked(uri)
      case _                    => Claim.Plain

  private def ui(uri: String): Boolean =
    uri.startsWith("ui://") && uri.length > "ui://".length && !uri.exists(c => c.isWhitespace || c == '#')
end Claim
