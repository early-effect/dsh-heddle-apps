package earlyeffect.dsh.apps

import zio.Chunk

/** What Save does to the live set. An unchanged server is `Keep`: same name, transport, and target. */
object ServerPlan:
  enum Step:
    case Keep(endpoint: Endpoint)
    case Stop(name: String)
    case Start(endpoint: Endpoint)

  /** The list line before the host has reported which servers connected. */
  def listed(total: Int): String =
    if total == 0 then "No servers"
    else if total == 1 then "1 server"
    else s"$total servers"

  def summary(total: Int, connected: Int): String =
    if total == 0 then "No servers"
    else
      val noun = if total == 1 then "1 server" else s"$total servers"
      s"$noun, $connected connected"

  def nameOf(endpoint: Endpoint): String =
    endpoint match
      case Endpoint.Http(name, _)       => name
      case Endpoint.Stdio(name, _, _, _) => name

  /** Stops first, then the next list in order. A changed target is a stop and a start, so the old session ends. */
  def steps(current: Chunk[Endpoint], next: Chunk[Endpoint]): Chunk[Step] =
    val byName = current.foldLeft(Map.empty[String, Endpoint])((acc, endpoint) => acc.updated(nameOf(endpoint), endpoint))
    val nextNames = next.map(nameOf).toSet
    val removed = current.collect {
      case endpoint if !nextNames.contains(nameOf(endpoint)) => Step.Stop(nameOf(endpoint))
    }
    val changed = next.collect {
      case endpoint if byName.get(nameOf(endpoint)).exists(_ != endpoint) => Step.Stop(nameOf(endpoint))
    }
    val forward = next.map { endpoint =>
      byName.get(nameOf(endpoint)) match
        case Some(old) if old == endpoint => Step.Keep(endpoint)
        case _                            => Step.Start(endpoint)
    }
    removed ++ changed ++ forward
end ServerPlan
