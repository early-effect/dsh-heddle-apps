package earlyeffect.dsh.apps.host

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** `DSH_HOME` and `HOME`. This import stays in the host bundle; the web client never references it. */
object NodeEnv:
  def get(name: String): Option[String] =
    NodeProcess.env.get(name).filter(_.nonEmpty)

  @js.native
  @JSImport("node:process", JSImport.Namespace)
  private object NodeProcess extends js.Object:
    def env: js.Dictionary[String] = js.native
