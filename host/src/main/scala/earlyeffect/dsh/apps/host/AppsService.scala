package earlyeffect.dsh.apps.host

import earlyeffect.dsh.apps.Descriptors
import earlyeffect.dsh.apps.facade.{Reflect, TypertRemoteService}
import scala.scalajs.js

/** The Cordis service `heddle-apps`. Methods are own properties, so a Typert call sees the shadow `this`. */
final class AppsService(owner: js.Object, bridge: HostBridge) extends TypertRemoteService(owner, Descriptors.service):
  install("names", bridge.names)
  install("open", bridge.open)
  install("status", bridge.status)

  private def install(name: String, value: js.Any): Unit =
    val installed = Reflect.set(this, name, value)
    if !installed then ()
