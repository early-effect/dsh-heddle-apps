package earlyeffect.dsh.apps.host

import earlyeffect.dsh.apps.{
  Endpoint, GrantCopy, JsJson, MountRef, Phase, PinsPath, Presentation, PublicName, RowReport, ServerPlan, ToolCopy,
}
import earlyeffect.dsh.apps.facade.{AsyncIter, AsyncSource, CallSelf, PluginContext, ToolRun}
import heddle.ChildCommand
import heddle.client.Client
import heddle.mcp.apps.{Clamp, HostPolicy, Sha256, UiMeta, UiPolicy}
import heddle.mcp.apps.host.{
  AppServer, AppsHost, Audit, ConsentMemory, HashPins, HostSettings, Launched, Mount, PinFiles, RemoteFrame, ServerName,
}
import heddle.mcp.apps.ui.HostContext
import heddle.mcp.client.{McpClient, McpError, McpStdio}
import heddle.mcp.protocol.{CallToolResult, Implementation, Message, Notifications, Resource, Tool}
import scala.scalajs.js
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** The host row: one Heddle session per configured server, one mounted view per call, one frame stream per view. */
final class HostBridge(ctx: PluginContext, endpoints: Chunk[Endpoint]):
  private val gate = new JsGate[Live]
  private val settings = HostSettings(
    Implementation("dsh-heddle-apps", "0.0.0"),
    HostPolicy.open,
    HostContext.empty,
    resourceSubscribe = true,
  )
  private var failure: Option[HostFailure] = None
  private var settled = false
  private var boot: Option[Fiber.Runtime[Nothing, Unit]] = None
  private var opened: List[Fiber.Runtime[Nothing, Unit]] = Nil
  private var wanted: Chunk[Endpoint] = endpoints
  private var rows = Chunk.empty[OpenServer]
  private var stops = Map.empty[String, Chunk[js.Function0[Unit]]]
  private var carrier: Option[(Scope, Client)] = None
  private var reports = Map.empty[String, RowReport]

  val names: js.Function0[js.Promise[js.Array[String]]] =
    () => gate.promise.`then`((live: Live) => live.names)

  /** One JSON document of [[RowReport]] for the servers Save last asked for. */
  val status: js.Function0[js.Promise[String]] =
    () => js.Promise.resolve[String](RowReport.document(reported).toJson)

  val open: js.ThisFunction1[CallSelf, String, js.Object] =
    (self, callId) => openStream(self, callId)

  def start(): Unit =
    val fiber = Edge.fork(program)
    boot = Some(fiber)

  def stop(): Unit =
    val fibers = boot.toList ++ opened
    val closing = rows
    rows = Chunk.empty
    Edge.fork(ZIO.foreachDiscard(fibers)(_.interrupt) *> ZIO.foreachDiscard(closing)(_.scope.close(Exit.unit)))

  /** Save. An unchanged server keeps its session. One failed start does not take the others down. */
  def replace(next: Chunk[Endpoint]): Unit =
    wanted = next
    Edge.fork(reconfigure(next))

  private def program: UIO[Unit] =
    PinsPath.from(NodeEnv.get("DSH_HOME"), NodeEnv.get("HOME")) match
      case None => ZIO.succeed(fail(HostFailure.NoHome))
      case Some(path) =>
        ZIO
          .scoped {
            ZIO.scopeWith { scope =>
              boot(path).flatMap { parts =>
                ZIO.succeed(complete(parts.at(scope))) *>
                  parts.audit.events.flatMap(_.foreach(event => ZIO.logInfo(s"dsh-heddle-apps: audit ${event.toJson}")))
              }
            }
          }
          .catchAll(err => ZIO.succeed(fail(err)))
          .onExit(_ => ZIO.succeed(fail(HostFailure.Stopped)))

  private def boot(path: String): ZIO[Scope, HostFailure, Parts] =
    val layer =
      Client.live.mapError(HostFailure.Client(_)) ++
        Audit.layer() ++
        ConsentMemory.layer ++
        HashPins.durable(PinFiles.at(path)).mapError(HostFailure.Pins(_)) ++
        ZLayer.fromZIO(AppsHost.make(settings))
    layer.build.flatMap { env =>
      connectAll.provideSome[Scope](ZLayer.succeedEnvironment(env)).flatMap { servers =>
        assemble(servers).provideEnvironment(env)
      }
    }

  private def connectAll: ZIO[Scope & Client, Nothing, Chunk[AppServer]] =
    for
      scope  <- ZIO.scope
      client <- ZIO.service[Client]
      _ = carrier = Some((scope, client))
      servers <- ZIO.foldLeft(wanted)(Chunk.empty[AppServer]) { (acc, endpoint) =>
        openRow(endpoint).map {
          case Some(server) => acc :+ server
          case None         => acc
        }
      }
    yield servers

  private def reconfigure(next: Chunk[Endpoint]): UIO[Unit] =
    val plan = ServerPlan.steps(rows.map(_.endpoint), next)
    ZIO.foreachDiscard(plan) {
      case ServerPlan.Step.Keep(_)          => ZIO.unit
      case ServerPlan.Step.Stop(name)       => stopRow(name)
      case ServerPlan.Step.Start(endpoint)  => startRow(endpoint)
    }

  private def stopRow(name: String): UIO[Unit] =
    rows.find(row => ServerPlan.nameOf(row.endpoint) == name) match
      case None => ZIO.unit
      case Some(row) =>
        rows = rows.filterNot(held => ServerPlan.nameOf(held.endpoint) == name)
        reports = reports.removed(name)
        val drop = stops.getOrElse(name, Chunk.empty)
        stops = stops.removed(name)
        ZIO.foreachDiscard(drop)(dispose => ZIO.succeed(locally(dispose()))) *> row.scope.close(Exit.unit)

  private def startRow(endpoint: Endpoint): UIO[Unit] =
    carrier match
      case None => ZIO.logWarning("dsh-heddle-apps: the host is not connected").unit
      case Some((scope, client)) =>
        openRow(endpoint).provideEnvironment(ZEnvironment(scope).add(client)).flatMap {
          case None         => ZIO.unit
          case Some(server) => register(Chunk(server)).unit
        }

  private def openRow(endpoint: Endpoint): ZIO[Scope & Client, Nothing, Option[AppServer]] =
    val name = ServerPlan.nameOf(endpoint)
    reports = reports.updated(name, RowReport(name, Phase.Connecting))
    for
      child  <- Scope.make
      _      <- ZIO.addFinalizer(child.close(Exit.unit))
      client <- ZIO.service[Client]
      opened <- connect(endpoint).provideSomeEnvironment[Scope](_.add(child).add(client)).either
      server <- opened match
        case Left(err) =>
          ZIO.succeed { reports = reports.updated(name, RowReport(name, Phase.Down(err.message))) } *>
            ZIO.logWarning(s"dsh-heddle-apps: $name: ${err.message}") *>
            child.close(Exit.unit).as(None)
        case Right(live) =>
          for
            described <- describe(live)
            _ <- live.session.notifications.foreach {
              case Message.Notification(Notifications.ToolsListChanged, _) => refresh(live)
              case _                                                       => ZIO.unit
            }.forkIn(child)
            _ <- ZIO.logInfo(s"dsh-heddle-apps: connected ${live.name.value}")
            _ <- ZIO.succeed {
              rows = rows :+ OpenServer(endpoint, live, child, described.titles)
              reports = reports.updated(name, RowReport(name, Phase.Up(described.lines, described.grant)))
            }
          yield Some(live)
    yield server

  private def refresh(server: AppServer): UIO[Unit] =
    val name = server.name.value
    val drop = stops.getOrElse(name, Chunk.empty)
    stops = stops.removed(name)
    ZIO.foreachDiscard(drop)(dispose => ZIO.succeed(locally(dispose()))) *>
      register(Chunk(server)) *>
      describe(server).flatMap { described =>
        ZIO.succeed {
          rows = rows.map(row => if row.server.name == server.name then row.copy(titles = described.titles) else row)
          reports = reports.updated(name, RowReport(name, Phase.Up(described.lines, described.grant)))
        }
      }

  private def reported: Chunk[RowReport] =
    wanted.map { endpoint =>
      val name = ServerPlan.nameOf(endpoint)
      reports.getOrElse(name, RowReport(name, Phase.Connecting))
    }

  /** Titles, the lines a person reads, and the clamped grant. A failed list leaves the card connected with no lines. */
  private def describe(server: AppServer): UIO[Described] =
    for
      tools     <- server.session.listTools.orElseSucceed(Chunk.empty)
      resources <- server.session.listResources.orElseSucceed(Chunk.empty)
      grant     <- grantOf(server, resources)
    yield
      val titles = resources.map(resource => resource.uri -> HostBridge.shownTitle(resource)).toMap
      val lines = tools.flatMap { tool =>
        val (ui, _) = UiMeta.decodeTool(tool.meta)
        if ui.resourceUri.isDefined && ui.visibility.model then Chunk(HostBridge.toolLine(tool)) else Chunk.empty
      }
      Described(titles, lines, grant)

  /** The union of connect-src across every app resource that answered. None when none of them could be read. */
  private def grantOf(server: AppServer, resources: Chunk[Resource]): UIO[Option[String]] =
    val apps = resources.filter(_.mimeType.contains(UiMeta.MimeType))
    if apps.isEmpty then ZIO.succeed(None)
    else
      ZIO
        .foldLeft(apps)((false, Set.empty[String])) { case ((seen, origins), resource) =>
          server.session.readResource(resource.uri).foldZIO(
            _ => ZIO.succeed((seen, origins)),
            contents =>
              contents.find(_.uri == resource.uri) match
                case None => ZIO.succeed((seen, origins))
                case Some(content) =>
                  val (ask, _) = UiMeta.decodeResource(content.meta)
                  val effective = Clamp[UiPolicy, HostPolicy].clamp(ask, HostPolicy.open)
                  ZIO.succeed((true, origins ++ effective.network.connect.map(_.render)))
              ,
          )
        }
        .map((seen, origins) => if seen then Some(GrantCopy.sentence(origins)) else None)

  private def connect(endpoint: Endpoint): ZIO[Scope & Client, McpError, AppServer] =
    val mcp = McpClient.Settings(
      Implementation("dsh-heddle-apps", "0.0.0"),
      handshake = McpClient.Handshake.Session,
    )
    val session = endpoint match
      case Endpoint.Http(_, url) => McpClient.http(url, mcp)
      case Endpoint.Stdio(_, command, args, cwd) =>
        McpStdio.spawn(ChildCommand(command, args, cwd = cwd), mcp)
    session.flatMap { live =>
      ZIO.fromEither(ServerName.from(HostBridge.endpointName(endpoint))).mapBoth(
        err => McpError.Protocol(err.message),
        name => AppServer(name, live),
      )
    }

  private def assemble(servers: Chunk[AppServer]): URIO[AppsHost & HashPins & ConsentMemory & Audit, Parts] =
    for
      host <- ZIO.service[AppsHost]
      pins <- ZIO.service[HashPins]
      memory <- ZIO.service[ConsentMemory]
      audit <- ZIO.service[Audit]
      mounts <- Ref.make(Map.empty[String, Mount])
      registered <- register(servers)
    yield Parts(registered, host, mounts, pins, memory, audit)

  private def register(servers: Chunk[AppServer]): UIO[js.Array[String]] =
    ZIO
      .foldLeft(servers)(Chunk.empty[String]) { (acc, server) =>
        server.session.listTools.foldZIO(
          err => ZIO.logWarning(s"dsh-heddle-apps: ${server.name.value}: ${err.message}").as(acc),
          tools => ZIO.foldLeft(tools)(acc)((names, tool) => linked(server, tool).map(names ++ _)),
        )
      }
      .map(names => js.Array(names*))

  private def linked(server: AppServer, tool: Tool): UIO[Chunk[String]] =
    val (ui, _) = UiMeta.decodeTool(tool.meta)
    (ui.resourceUri, ui.visibility.model) match
      case (Some(uri), true) =>
        val public = PublicName.of(server.name.value, tool.name.value)(HostBridge.hash12)
        registerTool(server, tool, uri.value, public).map(ok => if ok then Chunk(public) else Chunk.empty)
      case _ => ZIO.succeed(Chunk.empty)

  private def registerTool(server: AppServer, tool: Tool, ui: String, public: String): UIO[Boolean] =
    val definition = new RegisteredTool(
      name = public,
      description = HostBridge.toolLine(tool),
      parameters = JsJson.from(HostBridge.parametersOf(tool.inputSchema)),
      output = new ToolOutput(JsJson.from(Presentation.schema), render, presentationMeta),
      execute = (args, exec) => run(server, tool, ui, args, exec),
    )
    ZIO.attempt(ctx.tools.register(definition)).foldZIO(
      err => ZIO.logWarning(s"dsh-heddle-apps: did not register $public: ${HostBridge.detail(err)}").as(false),
      dispose =>
        val name = server.name.value
        stops = stops.updated(name, stops.getOrElse(name, Chunk.empty) :+ dispose)
        ZIO.logInfo(s"dsh-heddle-apps: registered $public").as(true),
    )

  private def run(server: AppServer, tool: Tool, ui: String, args: js.Any, exec: ToolRun): js.Promise[js.Any] =
    new js.Promise[js.Any]((resolve, reject) =>
      val fiber = Edge.fork {
        execute(server, tool, ui, args, exec).foldCauseZIO(
          cause => ZIO.succeed(reject(new js.Error(cause.failureOption.fold(HostFailure.Stopped.message)(_.message)))),
          value => ZIO.succeed(resolve(value)),
        )
      }
      opened = fiber :: opened
    )

  private def execute(server: AppServer, tool: Tool, ui: String, args: js.Any, exec: ToolRun): IO[HostFailure, js.Any] =
    for
      live <- awaitLive()
      input <- HostBridge.arguments(args)
      result <- server.session.callTool(tool.name, input).mapError(err => HostFailure.Tool(server.name.value, err))
      launched = Launched(tool.name, input, result)
      title = rows.find(_.server.name == server.name).flatMap(_.titles.get(ui))
      mount <- live.host
        .mount(server, launched)
        .mapError(HostFailure.Mount(_))
        .provide(ZLayer.succeed(live.pins) ++ ZLayer.succeed(live.audit))
      _ <- live.mounts.update(_.updated(exec.callId, mount))
      value = HostBridge.presented(MountRef(server.name.value, ui, exec.callId, title), result)
    yield value

  private def openStream(self: CallSelf, callId: String): AsyncPull =
    var waiterFiber: Option[Fiber.Runtime[Nothing, Unit]] = None
    var serveFiber: Option[Fiber.Runtime[Nothing, Unit]] = None
    val pull = new PullQueue(() =>
      waiterFiber.foreach(fiber => Edge.fork(fiber.interrupt.unit))
      serveFiber.foreach(fiber => Edge.fork(fiber.interrupt.unit))
    )
    uplink(self) match
      case None => pull.fail(new js.Error(HostFailure.Protocol.message))
      case Some(source) =>
        val iterator = source.asyncIterator()
        val waiter = Edge.fork(watch(callId, pull, iterator, fiber => serveFiber = Some(fiber)))
        waiterFiber = Some(waiter)
        opened = waiter :: opened
    pull.iterable

  private def watch(
      callId: String,
      pull: PullQueue,
      iterator: AsyncIter,
      onServe: Fiber.Runtime[Nothing, Unit] => Unit,
  ): UIO[Unit] =
    awaitLive().foldZIO(
      err => ZIO.succeed(pull.fail(new js.Error(err.message))),
      live =>
        live.mounts.get.flatMap { table =>
          table.get(callId) match
            case None => ZIO.succeed(pull.fail(new js.Error(HostFailure.MissingMount(callId).message)))
            case Some(mount) =>
              served(live, mount, pull, iterator)
                .foldZIO(
                  err => ZIO.succeed(pull.fail(new js.Error(err.message))),
                  _ => ZIO.succeed(pull.end()),
                )
                .forkIn(live.scope)
                .flatMap(fiber => ZIO.succeed(onServe(fiber)))
        },
    )

  /** `Mount.serve` returns once the view is listening. The Typert stream has to stay up until that mount ends, or the
    * view's `ui/initialize` arrives after the client handle has already closed.
    */
  private def served(live: Live, mount: Mount, pull: PullQueue, iterator: AsyncIter): ZIO[Any, McpError, Unit] =
    ZIO.scoped {
      RemoteFrame.connect(send(pull), incoming(iterator)).flatMap { remote =>
        remote
          .serve(mount)
          .provideSome[Scope](ZLayer.succeed(live.memory) ++ ZLayer.succeed(live.audit))
          .flatMap(_.ending)
          .debug("dsh-heddle-apps serve")
          .unit
      }
    }

  private def send(pull: PullQueue)(event: heddle.mcp.apps.host.FrameEvent): IO[McpError, Unit] =
    ZIO
      .fromEither(event.toJson.fromJson[Json].left.map(_ => McpError.Protocol("frame event")))
      .map(json => pull.offer(JsJson.from(json)))

  private def incoming(iterator: AsyncIter): ZStream[Any, McpError, heddle.mcp.apps.host.FrameEvent] =
    ZStream.repeatZIOOption {
      JsTask(iterator.next()).foldZIO(
        err =>
          ZIO.logWarning(s"dsh-heddle-apps: uplink read failed: ${HostBridge.detail(err)}") *>
            ZIO.fail(Some(McpError.Protocol("frame event"))),
        result =>
          if result.done.toOption.contains(true) then ZIO.logInfo("dsh-heddle-apps: uplink ended") *> ZIO.fail(None)
          else
            result.value.toOption match
              case None =>
                ZIO.logWarning("dsh-heddle-apps: uplink item missing") *>
                  ZIO.fail(Some(McpError.Protocol("frame event")))
              case Some(value) =>
                HostBridge.decode(value) match
                  case Right(event) =>
                    ZIO.logInfo(s"dsh-heddle-apps: uplink ${event.productPrefix}") *> ZIO.succeed(event)
                  case Left(err) =>
                    ZIO.logWarning(s"dsh-heddle-apps: uplink did not decode: ${err.message} ${HostBridge.snippet(value)}") *>
                      ZIO.fail(Some(err)),
      )
    }

  private def awaitLive(): IO[HostFailure, Live] =
    ZIO.async { callback =>
      gate.promise.`then`[Unit](
        (live: Live) => callback(ZIO.succeed(live)),
        (_: Any) => callback(ZIO.fail(failure.getOrElse(HostFailure.Stopped))),
      )
    }

  private def complete(live: Live): Unit =
    if !settled then
      settled = true
      gate.ok(live)

  private def fail(err: HostFailure): Unit =
    if !settled then
      settled = true
      failure = Some(err)
      gate.fail(new js.Error(err.message))

  private def uplink(self: CallSelf): Option[AsyncSource] =
    self.ctx.invocation.toOption.map(_.uplink())

  // DSH 0.1.7 snapshots this result and rejects any object whose prototype is not Object.prototype.
  /** A tool with a view is the frame. Text is only for a result that carries no view, and it is labeled. */
  private val render: js.Function2[js.Any, js.Any, js.Array[js.Any]] =
    (_, value) =>
      HostBridge.readMounted(value) match
        case Some(_) => js.Array()
        case None    => js.Array(js.Dynamic.literal(`type` = "text", text = s"Result\n${HostBridge.rendered(value)}"))

  private val presentationMeta: js.Function2[js.Any, js.Any, js.Any] =
    (_, value) =>
      HostBridge.readMounted(value) match
        case Some((ref, _)) => JsJson.from(Presentation.project(ref))
        case None => js.Dynamic.literal()
end HostBridge

object HostBridge:
  private def shownTitle(resource: Resource): String =
    resource.title.map(_.trim).filter(_.nonEmpty).getOrElse(resource.name)

  private def toolLine(tool: Tool): String =
    ToolCopy.line(tool.title, tool.description, tool.name.value)

  private def endpointName(endpoint: Endpoint): String =
    endpoint match
      case Endpoint.Http(name, _) => name
      case Endpoint.Stdio(name, _, _, _) => name

  private def hash12(raw: String): String =
    Sha256.hex(Chunk.fromArray(raw.getBytes("UTF-8"))).take(12)

  private def parametersOf(schema: Json.Obj): Json =
    if schema.fields.isEmpty then
      Json.Obj("type" -> Json.Str("object"), "additionalProperties" -> Json.Bool(true))
    else schema

  private def arguments(raw: js.Any): IO[HostFailure, Json.Obj] =
    ZIO.attempt(js.JSON.stringify(raw)).orElseFail(HostFailure.Arguments).flatMap { text =>
      if js.typeOf(text) != "string" then ZIO.fail(HostFailure.Arguments)
      else
        ZIO.fromEither(text.fromJson[Json].left.map(_ => HostFailure.Arguments)).flatMap {
          case obj: Json.Obj => ZIO.succeed(obj)
          case _ => ZIO.fail(HostFailure.Arguments)
        }
    }

  private def presented(ref: MountRef, result: CallToolResult): js.Any =
    JsJson.from(Presentation.value(ref, resultJson(result)))

  private def resultJson(result: CallToolResult): Json =
    result.structuredContent match
      case Some(obj) => obj
      case None =>
        Json.Obj(
          "content" -> Json.Arr(result.content.map(_.toJsonAST).collect { case Right(json) => json }),
          "isError" -> Json.Bool(result.failed),
        )

  private def readMounted(value: js.Any): Option[(MountRef, Json)] =
    val raw = js.JSON.stringify(value)
    if js.typeOf(raw) != "string" then None
    else raw.fromJson[Json].toOption.flatMap(json => Presentation.readValue(json).toOption)

  private def rendered(value: js.Any): String =
    readMounted(value) match
      case Some((_, result)) => result.toJson
      case None =>
        val raw = js.JSON.stringify(value)
        if js.typeOf(raw) == "string" then raw else ""

  private def decode(value: js.Any): Either[McpError, heddle.mcp.apps.host.FrameEvent] =
    val raw = js.JSON.stringify(value)
    if js.typeOf(raw) != "string" then Left(McpError.Protocol("frame event"))
    else raw.fromJson[heddle.mcp.apps.host.FrameEvent].left.map(reason => McpError.Protocol(reason))

  private def snippet(value: js.Any): String =
    val raw = js.JSON.stringify(value)
    if js.typeOf(raw) == "string" then raw.take(180) else js.typeOf(value)

  private def detail(err: Throwable): String =
    err match
      case thrown: js.JavaScriptException =>
        thrown.exception match
          case error: js.Error => error.message
          case other => other.getClass.getSimpleName
      case other => other.getClass.getSimpleName
end HostBridge

private final case class OpenServer(
    endpoint: Endpoint,
    server: AppServer,
    scope: Scope.Closeable,
    titles: Map[String, String],
)

private final case class Described(titles: Map[String, String], lines: Chunk[String], grant: Option[String])

private final case class Parts(
    names: js.Array[String],
    host: AppsHost,
    mounts: Ref[Map[String, Mount]],
    pins: HashPins,
    memory: ConsentMemory,
    audit: Audit,
):
  def at(scope: Scope): Live = Live(names, host, mounts, pins, memory, audit, scope)

private final case class Live(
    names: js.Array[String],
    host: AppsHost,
    mounts: Ref[Map[String, Mount]],
    pins: HashPins,
    memory: ConsentMemory,
    audit: Audit,
    scope: Scope,
)

final class RegisteredTool(
    val name: String,
    val description: String,
    val parameters: js.Any,
    val output: ToolOutput,
    val execute: js.Function2[js.Any, ToolRun, js.Promise[js.Any]],
) extends js.Object

final class ToolOutput(
    val schema: js.Any,
    val render: js.Function2[js.Any, js.Any, js.Array[js.Any]],
    val presentationMeta: js.Function2[js.Any, js.Any, js.Any],
) extends js.Object
