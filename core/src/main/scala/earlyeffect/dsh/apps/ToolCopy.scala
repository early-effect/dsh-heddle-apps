package earlyeffect.dsh.apps

/** The sentence a person reads for one tool. It is the summary that server published. */
object ToolCopy:
  def line(title: Option[String], description: Option[String], name: String): String =
    def text(raw: Option[String]): Option[String] = raw.map(_.trim).filter(_.nonEmpty)
    text(title).orElse(text(description)).getOrElse(name)
end ToolCopy
