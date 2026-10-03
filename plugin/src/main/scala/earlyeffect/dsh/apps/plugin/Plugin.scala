package earlyeffect.dsh.apps.plugin

import earlyeffect.dsh.apps.{Descriptors, JsJson, PluginConfig}
import earlyeffect.dsh.apps.facade.{Console, PluginContext}
import earlyeffect.dsh.apps.host.{AppsService, HostBridge}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import zio.json.*

/** The host plugin entry. `name` is the npm package the loader installs. */
object Plugin:
  @JSExportTopLevel("name")
  val name: String = Descriptors.packageName

  @JSExportTopLevel("inject")
  val inject: js.Array[String] = js.Array("tools", "typert")

  @JSExportTopLevel("parseServers")
  def parseServers(text: String): js.UndefOr[String] =
    PluginConfig.parse(text) match
      case Left(err) => err.message
      case Right(_)  => js.undefined

  @JSExportTopLevel("apply")
  def apply(ctx: PluginContext, config: js.Any): Unit =
    val raw = js.JSON.stringify(JsJson.plain(config))
    if js.typeOf(raw) != "string" then Console.error("dsh-heddle-apps: config is not json")
    else
      PluginConfig.parse(raw) match
        case Left(err)        => Console.error(s"dsh-heddle-apps: ${err.message}")
        case Right(endpoints) =>
          running match
            case Some(existing) => existing.replace(endpoints)
            case None           =>
              val bridge = new HostBridge(ctx, endpoints)
              running = Some(bridge)
              locally(ctx.on("loader/volatile-update", value => updated(bridge, value)))
              ctx.effect(() => start(ctx, bridge), "dsh-heddle-apps")
              ()
    end if
  end apply

  private var running: Option[HostBridge] = None

  private def updated(bridge: HostBridge, value: js.Any): Unit =
    val raw = js.JSON.stringify(JsJson.plain(value))
    if js.typeOf(raw) == "string" then
      val parsed = PluginConfig.parse(raw).orElse(PluginConfig.parse(s"""{"servers":$raw}"""))
      parsed.foreach(bridge.replace)

  private def start(ctx: PluginContext, bridge: HostBridge): js.Function0[Unit] =
    val service = new AppsService(ctx, bridge)
    val dispose = ctx.typert.register(js.JSON.parse(Descriptors.host.toJson))
    bridge.start()
    locally(service)
    () =>
      dispose()
      bridge.stop()
end Plugin
