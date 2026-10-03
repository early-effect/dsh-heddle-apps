package earlyeffect.dsh.apps

/** Where the host row stores hash pins. The directory is `DSH_HOME`, or `~/.dsh` when only a user home is known.
  */
object PinsPath:
  def from(dshHome: Option[String], userHome: Option[String]): Option[String] =
    val root = nonempty(dshHome).orElse(nonempty(userHome).map(home => s"$home/.dsh"))
    root.map(dir => s"$dir/heddle-apps/pins.json")

  private def nonempty(raw: Option[String]): Option[String] =
    raw.filter(_.nonEmpty)
end PinsPath
