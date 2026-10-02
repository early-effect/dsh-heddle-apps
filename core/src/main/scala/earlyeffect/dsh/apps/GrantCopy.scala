package earlyeffect.dsh.apps

/** The grant sentence on a server card. Empty connect-src is no network. There is no toggle. */
object GrantCopy:
  def sentence(connect: Set[String]): String =
    if connect.isEmpty then "Asks for no network."
    else s"May connect to ${connect.toList.sorted.mkString(", ")}."
end GrantCopy
