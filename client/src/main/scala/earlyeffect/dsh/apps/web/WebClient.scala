package earlyeffect.dsh.apps.web

import ascent.dom
import earlyeffect.dsh.apps.{
  ConsentCopy, Descriptors, DshServer, JsJson, Phase, PluginConfig, Presentation, RowReport, ServerDraft, ServerPlan,
}
import earlyeffect.dsh.apps.facade.{
  ClientContext, ConfigViewProps, Console, NamesResult, React, ReactRef, StreamHandle,
}
import heddle.mcp.apps.Origin
import heddle.mcp.apps.frame.{Frame, RelayMode}
import heddle.mcp.apps.host.{ConsentOutcome, ConsentRequest, FrameEvent}
import heddle.mcp.client.McpError
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.timers.{clearInterval, setInterval}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

final class ServersSlot extends js.Object:
  val name: String = "plugins.row.config"
  val key: String = "@early-effect/dsh-heddle-apps#heddle-apps"

/** The web row. It mounts the remote, claims one tool-view slot per public name, and frames that call. */
object WebClient:
  @JSExportTopLevel("inject")
  val inject: js.Array[String] = js.Array("slots", "remote")

  private val runtime = Runtime.default
  private var namespace: js.UndefOr[earlyeffect.dsh.apps.facade.Namespace] = js.undefined
  private var disposeMount: js.UndefOr[js.Function0[js.Any]] = js.undefined
  private val slots = js.Array[js.Function0[Unit]]()

  @JSExportTopLevel("apply")
  def apply(ctx: ClientContext): Unit =
    val dropServers = ctx.slots.inject(
      "plugins.row.config",
      () => ctx.slots.register(new ServersSlot, serversComponent),
    )
    val callback: js.Function0[js.Any] = () =>
      val mounted = ctx.remote.mount(payload)
      mounted.`then`[Unit](
        (dispose: js.Function0[js.Any]) =>
          disposeMount = dispose
          ctx.get(s"remote.${Descriptors.service}").foreach { remote =>
            namespace = remote
            remote.names().`then`[Unit]((result: NamesResult) => claim(ctx, result))
          }
        ,
        (error: Any) => Console.error(s"dsh-heddle-apps: remote mount failed: ${shown(error)}"),
      )
      () =>
        dropServers()
        release()
    ctx.effect(callback, "dsh-heddle-apps")
    ()

  private val serversComponent: js.Function1[ConfigViewProps, js.Object] =
    props =>
      if props.view == "summary" then React.createElement("span", js.undefined, ServerPlan.listed(draftsOf(props).length))
      else
        val host = React.useRef[js.UndefOr[dom.HTMLElement]](js.undefined)
        val status = formStatus(props)
        val revision = props.form.toOption.flatMap(_.state.revision.toOption).getOrElse(0.0)
        React.useLayoutEffect(() => mountServers(host, props), js.Array(props.view, status, revision))
        React.createElement("div", new HostProps(host))

  private def draftsOf(props: ConfigViewProps): Chunk[ServerDraft] =
    props.form.toOption.flatMap { form =>
      form.state.value.toOption.flatMap { section =>
        val raw = js.JSON.stringify(JsJson.plain(section))
        if js.typeOf(raw) != "string" then None
        else PluginConfig.parse(raw).toOption.map(_.map(ServerDraft.from))
      }
    }.getOrElse(Chunk.empty)

  /** The servers page. Save is the only write. Typing does not rebuild the card, so the cursor stays. */
  private def mountServers(host: ReactRef[js.UndefOr[dom.HTMLElement]], props: ConfigViewProps): js.Function0[Unit] =
    host.current.toOption match
      case None => () => ()
      case Some(parent) =>
        var drafts = draftsOf(props)
        var saved = drafts
        var reports = Chunk.empty[RowReport]
        var confirming: Option[Int] = None
        var focusNext = false
        var plates = Chunk.empty[Plate]
        var summaryNode: Option[dom.Element] = None
        val root = dom.document.createElement(dom.HtmlTag.div)
        root.className = "heddle-servers"
        ServersStyle.install()
        locally(parent.appendChild(root))
        def edit(index: Int)(change: ServerDraft => ServerDraft): Unit =
          drafts.lift(index).foreach { current =>
            drafts = drafts.updated(index, change(current))
            fill()
          }
        def drop(index: Int): Unit =
          drafts = drafts.take(index) ++ drafts.drop(index + 1)
          confirming = None
          paint()
        def move(index: Int, delta: Int): Unit =
          val to = index + delta
          drafts.lift(index) match
            case Some(item) if to >= 0 && to < drafts.length =>
              val rest = drafts.take(index) ++ drafts.drop(index + 1)
              drafts = rest.take(to) ++ Chunk(item) ++ rest.drop(to)
              confirming = None
              paint()
            case _ => ()
        def askRemove(index: Int): Unit =
          drafts.lift(index) match
            case None => ()
            case Some(draft) =>
              val connected = reports.find(_.name == draft.name).exists {
                case RowReport(_, Phase.Up(_, _)) => true
                case _                             => false
              }
              if draft.fresh || !connected then drop(index)
              else
                confirming = Some(index)
                paint()
        def fill(): Unit =
          val line =
            if loading(props) && drafts.isEmpty then "Loading the saved servers."
            else headline(drafts.length, reports)
          summaryNode.foreach(_.textContent = Some(line))
          drafts.zipWithIndex.foreach { (draft, index) =>
            plates.lift(index).foreach { plate =>
              plate.status.textContent = Some(liveSentence(draft, reports, saved))
              plate.tools.textContent = None
              plate.grant.textContent = None
              if !draft.fresh && !dirty(draft, saved) then
                reports.find(_.name == draft.name) match
                  case Some(RowReport(_, Phase.Up(tools, grant))) =>
                    tools.foreach { line =>
                      val item = dom.document.createElement(dom.HtmlTag.p)
                      item.textContent = Some(line)
                      locally(plate.tools.appendChild(item))
                    }
                    grant.foreach(text => plate.grant.textContent = Some(text))
                  case _ => ()
            }
          }
        def paint(): Unit =
          root.textContent = None
          plates = Chunk.empty
          val summary = dom.document.createElement(dom.HtmlTag.p)
          summary.className = "heddle-summary"
          summary.textContent = Some(if loading(props) && drafts.isEmpty then "Loading the saved servers." else headline(drafts.length, reports))
          summaryNode = Some(summary)
          locally(root.appendChild(summary))
          if drafts.isEmpty && !loading(props) then
            val empty = dom.document.createElement(dom.HtmlTag.p)
            empty.textContent = Some(
              "No servers yet. Add one. Each HTTP server is one process, shared by every client."
            )
            locally(root.appendChild(empty))
          var focus: Option[dom.HTMLInputElement] = None
          drafts.zipWithIndex.foreach { (draft, index) =>
            val pending = confirming.contains(index)
            val (box, plate, nameInput) = serverCard(
              draft,
              index,
              pending,
              edit,
              if pending then drop else askRemove,
              () => { confirming = None; paint() },
              move,
              paint,
            )
            plates = plates :+ plate
            locally(root.appendChild(box))
            if focusNext && index == drafts.length - 1 then focus = Some(nameInput)
          }
          val error = dom.document.createElement(dom.HtmlTag.p)
          error.className = "heddle-error"
          locally(root.appendChild(error))
          val actions = dom.document.createElement(dom.HtmlTag.div)
          actions.className = "heddle-row"
          locally(actions.appendChild(button("Add a server", () =>
            focusNext = true
            drafts = drafts :+ ServerDraft.blank
            paint()
          )))
          locally(actions.appendChild(button("Save", () =>
            saveDrafts(props, drafts, error, () =>
              saved = drafts.map(_.copy(fresh = false))
              drafts = saved
              fill()
            )
          )))
          locally(root.appendChild(actions))
          focusNext = false
          focus.foreach(_.focus())
        paint()
        def accept(next: Chunk[RowReport]): Unit =
          reports = next
          val loaded = draftsOf(props)
          if drafts.isEmpty && loaded.nonEmpty then
            drafts = loaded
            saved = loaded
            paint()
          else if plates.length == drafts.length then fill()
          else paint()
        val ticker = setInterval(1000)(pullStatus(accept))
        pullStatus(accept)
        () =>
          clearInterval(ticker)
          remove(root)

  private def saveDrafts(
      props: ConfigViewProps,
      drafts: Chunk[ServerDraft],
      error: dom.Element,
      onSaved: () => Unit,
  ): Unit =
    val document = Json.Obj("servers" -> Json.Arr(drafts.map(_.row)))
    PluginConfig.parse(document.toJson) match
      case Left(err) => error.textContent = Some(err.message)
      case Right(_) =>
        error.textContent = None
        onSaved()
        props.form.toOption.foreach { form =>
          val op = js.Dynamic.literal(
            op = "set",
            path = js.Array("servers"),
            value = JsJson.from(Json.Arr(drafts.map(_.row))),
          )
          locally(form.mutate(js.Array(op), form.state.revision))
        }

  private def serverCard(
      draft: ServerDraft,
      index: Int,
      pending: Boolean,
      edit: Int => (ServerDraft => ServerDraft) => Unit,
      removeAt: Int => Unit,
      cancel: () => Unit,
      move: (Int, Int) => Unit,
      paint: () => Unit,
  ): (dom.Element, Plate, dom.HTMLInputElement) =
    val box = dom.document.createElement(dom.HtmlTag.section)
    box.className = "heddle-card"
    box.addEventListener(
      "keydown",
      event =>
        event match
          case key: dom.KeyboardEvent if key.key == "Escape" && draft.fresh => removeAt(index)
          case _                                                             => ()
      ,
    )
    val (nameWrap, nameInput) = labeled("Name", draft.name, "Letters, digits, _, and -, at most 32.", None)
    val nameError = dom.document.createElement(dom.HtmlTag.p)
    nameInput.addEventListener(
      "input",
      _ =>
        edit(index)(_.copy(name = nameInput.value))
        nameError.textContent =
          if nameInput.value.isEmpty then None
          else DshServer.from(nameInput.value).left.toOption.map(_.message)
      ,
    )
    locally(box.appendChild(nameWrap))
    nameError.className = "heddle-error"
    locally(box.appendChild(nameError))
    val choice = dom.document.createElement(dom.HtmlTag.div)
    choice.className = "heddle-row"
    val http = button("HTTP", () =>
      edit(index)(_.show("http"))
      paint()
    )
    val stdio = button("stdio", () =>
      edit(index)(_.show("stdio"))
      paint()
    )
    http.setAttribute("aria-pressed", if draft.shown == "http" then "true" else "false")
    stdio.setAttribute("aria-pressed", if draft.shown == "stdio" then "true" else "false")
    locally(choice.appendChild(http))
    locally(choice.appendChild(stdio))
    locally(box.appendChild(choice))
    if draft.shown == "stdio" then
      val (commandWrap, commandInput) = labeled("Command", draft.command, "Its own process.", None)
      commandInput.addEventListener("input", _ => edit(index)(_.copy(command = commandInput.value)))
      locally(box.appendChild(commandWrap))
      locally(box.appendChild(arguments(draft.args, index, edit, paint)))
      val (cwdWrap, cwdInput) = labeled("Working directory", draft.cwd, "Optional. An empty one is left out.", None)
      cwdInput.addEventListener("input", _ => edit(index)(_.copy(cwd = cwdInput.value)))
      locally(box.appendChild(cwdWrap))
    else
      val (urlWrap, urlInput) =
        labeled("URL", draft.url, "One process, shared by every client of that server.", Some(ServerDraft.exampleUrl))
      urlInput.addEventListener("input", _ => edit(index)(_.copy(url = urlInput.value)))
      locally(box.appendChild(urlWrap))
    val status = dom.document.createElement(dom.HtmlTag.p)
    status.className = "heddle-status"
    val tools = dom.document.createElement(dom.HtmlTag.div)
    tools.className = "heddle-tools"
    val grant = dom.document.createElement(dom.HtmlTag.p)
    grant.className = "heddle-grant"
    locally(box.appendChild(status))
    locally(box.appendChild(tools))
    locally(box.appendChild(grant))
    if pending then
      val question = dom.document.createElement(dom.HtmlTag.p)
      question.textContent = Some(RowReport.removeQuestion(if draft.name.isEmpty then "this server" else draft.name))
      locally(box.appendChild(question))
      locally(box.appendChild(button("Cancel", cancel)))
    val rowActions = dom.document.createElement(dom.HtmlTag.div)
    rowActions.className = "heddle-row"
    locally(rowActions.appendChild(button("Remove", () => removeAt(index))))
    locally(rowActions.appendChild(button("Move up", () => move(index, -1))))
    locally(rowActions.appendChild(button("Move down", () => move(index, 1))))
    locally(box.appendChild(rowActions))
    (box, new Plate(status, tools, grant), nameInput)

  private def arguments(
      args: Chunk[String],
      index: Int,
      edit: Int => (ServerDraft => ServerDraft) => Unit,
      paint: () => Unit,
  ): dom.Element =
    val box = dom.document.createElement(dom.HtmlTag.div)
    val label = dom.document.createElement(dom.HtmlTag.p)
    label.textContent = Some("Arguments")
    locally(box.appendChild(label))
    args.zipWithIndex.foreach { (arg, argIndex) =>
      val (wrap, input) = labeled(s"Argument ${argIndex + 1}", arg, "", None)
      input.addEventListener(
        "input",
        _ =>
          edit(index) { row =>
            if argIndex >= 0 && argIndex < row.args.length then row.copy(args = row.args.updated(argIndex, input.value))
            else row
          }
        ,
      )
      locally(box.appendChild(wrap))
      locally(box.appendChild(button("Remove argument", () =>
        edit(index)(row => row.copy(args = row.args.take(argIndex) ++ row.args.drop(argIndex + 1)))
        paint()
      )))
      locally(box.appendChild(button("Move argument up", () =>
        shiftArg(index, argIndex, -1, edit)
        paint()
      )))
      locally(box.appendChild(button("Move argument down", () =>
        shiftArg(index, argIndex, 1, edit)
        paint()
      )))
    }
    locally(box.appendChild(button("Add an argument", () =>
      edit(index)(row => row.copy(args = row.args :+ ""))
      paint()
    )))
    box

  private def shiftArg(
      index: Int,
      argIndex: Int,
      delta: Int,
      edit: Int => (ServerDraft => ServerDraft) => Unit,
  ): Unit =
    val to = argIndex + delta
    edit(index) { row =>
      row.args.lift(argIndex) match
        case Some(item) if to >= 0 && to < row.args.length =>
          val rest = row.args.take(argIndex) ++ row.args.drop(argIndex + 1)
          row.copy(args = rest.take(to) ++ Chunk(item) ++ rest.drop(to))
        case _ => row
    }

  private def labeled(
      label: String,
      value: String,
      hint: String,
      placeholder: Option[String],
  ): (dom.Element, dom.HTMLInputElement) =
    val wrap = dom.document.createElement(dom.HtmlTag.label)
    wrap.textContent = Some(label)
    val input = dom.document.createElement(dom.HtmlTag.input)
    input.setAttribute("value", value)
    placeholder.filter(_ => value.isEmpty).foreach(text => input.setAttribute("placeholder", text))
    locally(wrap.appendChild(input))
    if hint.nonEmpty then
      val note = dom.document.createElement(dom.HtmlTag.p)
      note.textContent = Some(hint)
      locally(wrap.appendChild(note))
    (wrap, input)

  private def formStatus(props: ConfigViewProps): String =
    props.form.toOption.flatMap(_.state.status.toOption).getOrElse("missing")

  private def loading(props: ConfigViewProps): Boolean =
    formStatus(props) == "loading"

  private def headline(total: Int, reports: Chunk[RowReport]): String =
    if reports.isEmpty then ServerPlan.listed(total)
    else
      val connected = reports.count {
        case RowReport(_, Phase.Up(_, _)) => true
        case _                             => false
      }
      ServerPlan.summary(total, connected)

  private def liveSentence(draft: ServerDraft, reports: Chunk[RowReport], saved: Chunk[ServerDraft]): String =
    val live = reports.find(_.name == draft.name).fold("Connecting.")(report => RowReport.status(report.phase))
    if draft.fresh then "Not live yet."
    else if dirty(draft, saved) then RowReport.edited(live)
    else live

  private def dirty(draft: ServerDraft, saved: Chunk[ServerDraft]): Boolean =
    saved.find(_.name == draft.name) match
      case Some(old) => old.copy(fresh = false) != draft.copy(fresh = false)
      case None      => true

  private def pullStatus(use: Chunk[RowReport] => Unit): Unit =
    namespace.toOption match
      case None => ()
      case Some(remote) =>
        remote.status().`then`[Unit](
          (result: js.Any) => statusText(result).flatMap(text => RowReport.read(text).toOption).foreach(use),
          (_: Any) => (),
        )

  /** Typert may hand back the JSON text, or `{ok, value}` around that text or the array. */
  private def statusText(result: js.Any): Option[String] =
    val raw = js.JSON.stringify(result)
    if js.typeOf(raw) != "string" then None
    else
      raw.fromJson[Json].toOption.flatMap {
        case Json.Str(text) => Some(text)
        case arr: Json.Arr  => Some(arr.toJson)
        case obj: Json.Obj  =>
          obj.fields.collectFirst { case ("value", Json.Str(text)) => text }.orElse {
            obj.fields.collectFirst { case ("value", value) => value.toJson }
          }
        case _ => None
      }

  private def button(text: String, action: () => Unit): dom.Element =
    val node = dom.document.createElement(dom.HtmlTag.button)
    node.textContent = Some(text)
    node.setAttribute("type", "button")
    node.addEventListener("click", _ => action())
    node

  private def claim(ctx: ClientContext, result: NamesResult): Unit =
    if result.ok && js.Array.isArray(result.value) then
      result.value.foreach { name =>
        val dispose = ctx.slots.inject(
          "tool.call.toolview",
          () => ctx.slots.register(new SlotKey(name), view),
        )
        slots.push(dispose)
      }

  private def release(): Unit =
    slots.foreach(_())
    slots.clear()
    disposeMount.foreach(dispose => locally(dispose()))
    disposeMount = js.undefined
    namespace = js.undefined

  private val frameComponent: js.Function1[FrameProps, js.Object] =
    props =>
      val host = React.useRef[js.UndefOr[dom.HTMLElement]](js.undefined)
      React.useLayoutEffect(
        () => startFrame(host, props.callId, props.title),
        js.Array(props.callId),
      )
      React.createElement("div", new HostProps(host))

  private val frameView: js.Any = React.memo(frameComponent)

  private val view: js.Function1[ToolViewProps, js.Object] =
    props =>
      mountRef(props) match
        case Some(ref) =>
          React.createElement(frameView, new FrameProps(ref.callId, ref.title.getOrElse("")))
        case None => React.createElement("div", js.undefined, "Opening")

  private def startFrame(
      host: ReactRef[js.UndefOr[dom.HTMLElement]],
      callId: String,
      title: String,
  ): js.Function0[Unit] =
    host.current.toOption match
      case None => () => ()
      case Some(parent) =>
        namespace.toOption match
          case None =>
            parent.textContent = Some("The host is not connected. Check this server on the Plugins page.")
            () => parent.textContent = None
          case Some(remote) =>
            mountDock(parent, remote, callId, title)

  /** Standard mode sets `hidden` on a finished tool call. The spacer follows that call out of the hidden ancestor.
    * The iframe stays on `document.body`. Firefox reloads a sandboxed `srcdoc` iframe when it is reparented, and a
    * reloaded relay never receives the view again.
    */
  private def mountDock(
      parent: dom.HTMLElement,
      remote: earlyeffect.dsh.apps.facade.Namespace,
      callId: String,
      title: String,
  ): js.Function0[Unit] =
    var live = true
    var held = 0
    val slot = dom.document.createElement(dom.HtmlTag.div)
    slot.setAttribute("data-heddle-slot", "")
    slot.setAttribute("style", "min-height:360px")
    locally(parent.appendChild(slot))
    val opening = dom.document.createElement(dom.HtmlTag.p)
    opening.textContent = Some("Opening")
    val question = dom.document.createElement(dom.HtmlTag.div)
    val frame = dom.document.createElement(dom.HtmlTag.div)
    frame.setAttribute("style", "min-height:360px")
    val dock = dom.document.createElement(dom.HtmlTag.div)
    dock.setAttribute("data-heddle-app", "")
    if title.nonEmpty then
      val heading = dom.document.createElement(dom.HtmlTag.div)
      heading.textContent = Some(title)
      heading.setAttribute("style", "padding:8px 12px 0;font-weight:600")
      locally(dock.appendChild(heading))
    locally(dock.appendChild(opening))
    locally(dock.appendChild(question))
    locally(dock.appendChild(frame))
    def sync(): Unit =
      if live && slot.isConnected then
        val rect = slot.getBoundingClientRect()
        dock.setAttribute(
          "style",
          s"position:fixed;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;z-index:2;background:#fff",
        )
        val height = Math.round(dock.getBoundingClientRect().height).toInt
        if height > 0 && height != held then
          held = height
          slot.setAttribute("style", s"min-height:${height}px")
    def follow(): Unit =
      if live && parent.isConnected then
        place(parent, slot)
        sync()
    val watch = new dom.MutationObserver((_, _) => if live then follow())
    parent.closest("[data-chat-flow]").foreach { column =>
      val options = new dom.MutationObserverInit {}
      options.attributes = true
      options.attributeFilter = js.Array("hidden")
      options.childList = true
      options.subtree = true
      watch.observe(column, options)
    }
    val boxes = new dom.ResizeObserver((_, _) => if live then sync())
    val onMove: js.Function1[dom.Event, Unit] = _ => if live then sync()
    val listenOpts = new dom.AddEventListenerOptions {}
    listenOpts.capture = true
    def stop(): Unit =
      live = false
      watch.disconnect()
      boxes.disconnect()
      dom.window.removeEventListener("scroll", onMove, listenOpts)
      dom.window.removeEventListener("resize", onMove)
      remove(slot)
      remove(dock)
    dom.document.body match
      case None =>
        frame.textContent = Some("this page has no document to keep the view in")
        () => stop()
      case Some(body) =>
        locally(body.appendChild(dock))
        boxes.observe(slot)
        boxes.observe(dock)
        dom.window.addEventListener("scroll", onMove, listenOpts)
        dom.window.addEventListener("resize", onMove)
        follow()
        dom.window.queueMicrotask(() => if live then follow())
        Origin.from(dom.window.location.origin) match
          case Left(_) =>
            frame.textContent = Some("this page has no origin the frame can use")
            () => stop()
          case Right(origin) =>
            val handle = remote.open(callId)
            val fiber = fork {
              ZIO.scoped {
                Frame
                  .follow(
                    frame,
                    origin,
                    RelayMode.Opaque,
                    uplink(handle),
                    incoming(handle),
                    ask(question, handle),
                  )
                  .foldZIO(
                    err =>
                      ZIO.succeed {
                        remove(opening)
                        frame.textContent = Some(err.message)
                      },
                    _ => ZIO.succeed(remove(opening)) *> ZIO.never,
                  )
              }
            }
            () =>
              stop()
              fork(fiber.interrupt.unit)
              handle.dispose()

  private def place(seat: dom.HTMLElement, slot: dom.HTMLElement): Unit =
    if seat.isConnected then
      if !covered(seat) then adopt(seat, slot)
      else
        outermostHidden(seat) match
          case Some(hidden) =>
            hidden.parentNode match
              case Some(parent) => parkAfter(parent, hidden, slot)
              case None         => adopt(seat, slot)
          case None => adopt(seat, slot)

  private def covered(node: dom.Node): Boolean =
    def loop(current: Option[dom.Element]): Boolean =
      current match
        case None => false
        case Some(element) => element.hasAttribute("hidden") || loop(element.parentElement)
    loop(node match
      case element: dom.Element => Some(element)
      case _ => None
    )

  private def outermostHidden(start: dom.Element): Option[dom.Element] =
    def loop(current: Option[dom.Element], found: Option[dom.Element]): Option[dom.Element] =
      current match
        case None => found
        case Some(element) =>
          val next = if element.hasAttribute("hidden") then Some(element) else found
          loop(element.parentElement, next)
    loop(Some(start), None)

  private def adopt(parent: dom.Node, dock: dom.Node): Unit =
    if !dock.parentNode.exists(_ eq parent) then locally(parent.appendChild(dock))

  private def parkAfter(parent: dom.Node, hidden: dom.Node, dock: dom.Node): Unit =
    hidden.nextSibling match
      case Some(next) if next eq dock => ()
      case Some(next) => locally(parent.insertBefore(dock, next))
      case None => adopt(parent, dock)

  private def ask(
      question: dom.HTMLElement,
      handle: StreamHandle,
  ): (Long, ConsentRequest) => UIO[Unit] =
    (id, request) =>
      ZIO.succeed {
        val bar = dom.document.createElement(dom.HtmlTag.div)
        bar.setAttribute("style", "display:flex;gap:8px;align-items:center;padding:8px")
        val label = dom.document.createElement(dom.HtmlTag.div)
        label.textContent = Some(ConsentCopy.question(request.summary, request.tool.value, request.server.value))
        locally(bar.appendChild(label))
        ConsentCopy.lines(request.arguments).foreach { line =>
          val row = dom.document.createElement(dom.HtmlTag.div)
          row.textContent = Some(line)
          locally(bar.appendChild(row))
        }
        def button(text: String, outcome: ConsentOutcome, focus: Boolean): Unit =
          val node = dom.document.createElement(dom.HtmlTag.button)
          node.textContent = Some(text)
          node.setAttribute("type", "button")
          node.addEventListener(
            "click",
            _ =>
              if outcome == ConsentOutcome.Rejected then label.textContent = Some(ConsentCopy.denied)
              fork(uplink(handle)(FrameEvent.Answer(id, outcome)).ignore)
              if outcome == ConsentOutcome.Rejected then removeButtons(bar) else remove(bar)
            ,
          )
          locally(bar.appendChild(node))
          if focus then node.focus()
        button("Allow once", ConsentOutcome.AllowOnce, focus = true)
        button("Allow for this session", ConsentOutcome.AllowForSession, focus = false)
        button("Don't allow", ConsentOutcome.Rejected, focus = false)
        locally(question.appendChild(bar))
      }

  private def uplink(handle: StreamHandle)(event: FrameEvent): IO[McpError, Unit] =
    ZIO
      .fromEither(event.toJson.fromJson[Json].left.map(reason => McpError.Protocol(reason)))
      .flatMap { json =>
        ZIO.attempt(handle.send(JsJson.from(json))).mapError(err => McpError.Protocol(shown(err)))
      }
      .debug(s"dsh-heddle-apps uplink ${eventLabel(event)}")

  private def incoming(handle: StreamHandle): ZStream[Any, McpError, FrameEvent] =
    val iterator = handle.asyncIterator()
    ZStream.repeatZIOOption {
      JsPromise(iterator.next()).foldZIO(
        _ => ZIO.fail(Some(McpError.Protocol("frame event"))),
        result =>
          if result.done.toOption.contains(true) then ZIO.fail(None)
          else
            result.value.toOption.flatMap(value => decode(value).toOption) match
              case Some(event) => ZIO.succeed(event)
              case None => ZIO.fail(Some(McpError.Protocol("frame event"))),
      )
    }

  private def mountRef(props: ToolViewProps): Option[earlyeffect.dsh.apps.MountRef] =
    props.block.toOption.flatMap(_.meta.toOption).flatMap { meta =>
      val raw = js.JSON.stringify(meta)
      if js.typeOf(raw) != "string" then None
      else raw.fromJson[Json].toOption.flatMap(json => Presentation.read(json).toOption)
    }

  private def payload: js.Object =
    new RemoteMount(
      Descriptors.packageName,
      js.Array(strict(Descriptors.names), strict(Descriptors.open), strict(Descriptors.status)),
    )

  private def shown(error: Any): String =
    error match
      case thrown: js.JavaScriptException =>
        thrown.exception match
          case value: js.Error => value.message
          case other => String.valueOf(other)
      case value: js.Error => value.message
      case other => other.getClass.getSimpleName

  /** Leaves the question, which denial has already replaced with "Not allowed." */
  private def removeButtons(bar: dom.Element): Unit =
    def rest(): Unit =
      if bar.childElementCount > 1 then
        bar.lastChild match
          case Some(node) =>
            remove(node)
            rest()
          case None => ()
      else ()
    rest()

  private def remove(node: dom.Node): Unit =
    node.parentNode.foreach { parent =>
      locally(parent.removeChild(node))
    }

  private def fork(effect: UIO[Unit]): Fiber.Runtime[Nothing, Unit] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.fork(effect)
    }

  private def eventLabel(event: FrameEvent): String =
    event match
      case FrameEvent.FromView(heddle.mcp.protocol.Message.Request(_, method, _))      => s"FromView $method"
      case FrameEvent.FromView(heddle.mcp.protocol.Message.Notification(method, _))    => s"FromView $method"
      case FrameEvent.FromView(_)                                                      => "FromView"
      case FrameEvent.Answer(_, outcome)                                               => s"Answer ${outcome.productPrefix}"
      case other                                                                       => other.productPrefix

  private def decode(value: js.Any): Either[McpError, FrameEvent] =
    val raw = js.JSON.stringify(value)
    if js.typeOf(raw) != "string" then Left(McpError.Protocol("frame event"))
    else raw.fromJson[FrameEvent].left.map(_ => McpError.Protocol("frame event"))

  /** Replaces a one-field `src-json` codec with the strict codec `$mount` accepts. */
  private def strict(json: Json): js.Any =
    json match
      case obj: Json.Obj if srcJson(obj) =>
        js.Dictionary[js.Any](
          "mode" -> "strict",
          "typeSymbol" -> "unknown",
          "create" -> (() => new AcceptAny),
        )
      case obj: Json.Obj =>
        val fields = js.Dictionary.empty[js.Any]
        obj.fields.foreach((key, value) => fields(key) = strict(value))
        fields
      case Json.Arr(items) => js.Array(items.map(strict)*)
      case other => JsJson.from(other)

  private def srcJson(obj: Json.Obj): Boolean =
    obj.fields.length == 1 && obj.fields.headOption.exists((key, value) => key == "mode" && value == Json.Str("src-json"))
end WebClient

/** What a strict codec's `create()` returns. The gateway calls `parse` and keeps the value. */
final class AcceptAny extends js.Object:
  def parse(value: js.Any): js.Any = value

final class RemoteMount(val `package`: String, val descriptors: js.Array[js.Any]) extends js.Object

final class SlotKey(val key: String) extends js.Object:
  val name: String = "tool.call.toolview"

final class FrameProps(val callId: String, val title: String) extends js.Object

final class HostProps(val ref: ReactRef[js.UndefOr[dom.HTMLElement]]) extends js.Object

final class Plate(val status: dom.Element, val tools: dom.Element, val grant: dom.Element)

/** Layout for the servers page. The Plugins page supplies the type; this only stacks the fields. */
private object ServersStyle:
  val css: String =
    """
    |.heddle-servers { display: flex; flex-direction: column; gap: 16px; max-width: 36rem; }
    |.heddle-servers p { margin: 0; }
    |.heddle-servers .heddle-card { display: flex; flex-direction: column; gap: 12px; padding: 16px; border: 1px solid color-mix(in srgb, currentColor 16%, transparent); border-radius: 12px; }
    |.heddle-servers label { display: flex; flex-direction: column; align-items: stretch; gap: 6px; font-size: 0.85rem; }
    |.heddle-servers input { font: inherit; padding: 8px 10px; border-radius: 8px; border: 1px solid color-mix(in srgb, currentColor 24%, transparent); background: transparent; color: inherit; width: 100%; box-sizing: border-box; }
    |.heddle-servers .heddle-row { display: flex; flex-wrap: wrap; gap: 8px; }
    |.heddle-servers button { font: inherit; padding: 6px 12px; border-radius: 8px; border: 1px solid color-mix(in srgb, currentColor 24%, transparent); background: transparent; color: inherit; cursor: pointer; }
    |.heddle-servers button[aria-pressed="true"] { background: color-mix(in srgb, currentColor 12%, transparent); }
    |.heddle-servers .heddle-tools { display: flex; flex-direction: column; gap: 4px; }
    |.heddle-servers .heddle-error:empty { display: none; }
    |""".stripMargin

  def install(): Unit =
    if dom.document.querySelector("#heddle-servers-style").isEmpty then
      val node = dom.document.createElement(dom.HtmlTag.style)
      node.id = "heddle-servers-style"
      node.textContent = Some(css)
      dom.document.head.foreach(head => locally(head.appendChild(node)))

@js.native
trait ToolViewProps extends js.Object:
  val toolName: String = js.native
  val block: js.UndefOr[ToolBlock] = js.native

@js.native
trait ToolBlock extends js.Object:
  val meta: js.UndefOr[js.Any] = js.native
  val content: js.UndefOr[js.Any] = js.native

object JsPromise:
  def apply[A](promise: => js.Promise[A]): Task[A] =
    ZIO.async { callback =>
      promise.`then`[Unit](
        (value: A) => callback(ZIO.succeed(value)),
        (error: Any) => callback(ZIO.fail(js.JavaScriptException(error))),
      )
    }
