package earlyeffect.dsh.apps.facade

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobal, JSImport, JSName}

/** The global `Reflect`, so a method can be installed as an own property without a cast. */
@js.native
@JSGlobal("Reflect")
object Reflect extends js.Object:
  def set(target: js.Object, property: String, value: js.Any): Boolean = js.native

@js.native
@JSGlobal("console")
object Console extends js.Object:
  def error(message: String): Unit = js.native

/** What a Typert call applies a method to. `invocation` lives on the call's context, not on the service. */
@js.native
trait CallSelf extends js.Object:
  val ctx: CallContext = js.native

@js.native
trait CallContext extends js.Object:
  val invocation: js.UndefOr[Invocation] = js.native

@js.native
trait Invocation extends js.Object:
  def uplink(): AsyncSource = js.native

@js.native
trait AsyncSource extends js.Object:
  @JSName(js.Symbol.asyncIterator)
  def asyncIterator(): AsyncIter = js.native

@js.native
trait AsyncIter extends js.Object:
  def next(): js.Promise[IterResult] = js.native

@js.native
trait IterResult extends js.Object:
  val done: js.UndefOr[Boolean] = js.native
  val value: js.UndefOr[js.Any] = js.native

/** Cordis service base that publishes `typertRemote`. The TS constructor is protected; the JS class is callable. */
@js.native
@JSImport("@deepseek-ai/dsh-typert-protocol", "TypertRemoteService")
class TypertRemoteService(@unused owner: js.Object, @unused serviceKey: String) extends js.Object

@js.native
trait PluginContext extends js.Object:
  val tools: Tools                                                               = js.native
  val typert: Typert                                                             = js.native
  def effect(callback: js.Function0[js.Any], label: String): js.Function0[Unit]  = js.native
  def on(name: String, listener: js.Function1[js.Any, Unit]): js.Function0[Unit] = js.native

/** What the Plugins page passes a `plugins.row.config` entry. */
@js.native
trait ConfigViewProps extends js.Object:
  val view: String                     = js.native
  val form: js.UndefOr[ConfigPageForm] = js.native

@js.native
trait ConfigPageForm extends js.Object:
  val state: ConfigPageState                                                              = js.native
  def mutate(ops: js.Array[js.Object], revision: js.UndefOr[Double]): js.Promise[Boolean] = js.native

@js.native
trait ConfigPageState extends js.Object:
  val status: js.UndefOr[String]   = js.native
  val value: js.UndefOr[js.Object] = js.native
  val revision: js.UndefOr[Double] = js.native

@js.native
trait Tools extends js.Object:
  def register(definition: js.Object): js.Function0[Unit] = js.native

@js.native
trait Typert extends js.Object:
  def register(contribution: js.Any): js.Function0[Unit] = js.native

/** `execute`'s second argument. `callId` is the only field this host reads. */
@js.native
trait ToolRun extends js.Object:
  val callId: String = js.native

@js.native
trait ClientContext extends js.Object:
  val slots: Slots                                                              = js.native
  val remote: RemoteApi                                                         = js.native
  def get(name: String): js.UndefOr[Namespace]                                  = js.native
  def effect(callback: js.Function0[js.Any], label: String): js.Function0[Unit] = js.native
  def provide(name: String, value: js.Any): js.Any                              = js.native

@js.native
trait Slots extends js.Object:
  def inject(key: String, callback: js.Function0[js.Any]): js.Function0[Unit] = js.native
  def register(options: js.Object, component: js.Any): js.Function0[Unit]     = js.native

@js.native
trait RemoteApi extends js.Object:
  @JSName("$mount")
  def mount(contribution: js.Object): js.Promise[js.Function0[js.Any]] = js.native

/** Methods installed by `$mount` on `remote.heddle-apps`. `names` resolves to `{ok, value}`. `open` returns the handle.
  */
@js.native
trait Namespace extends js.Object:
  def names(): js.Promise[NamesResult]   = js.native
  def open(callId: String): StreamHandle = js.native
  def status(): js.Promise[js.Any]       = js.native

@js.native
trait NamesResult extends js.Object:
  val ok: Boolean             = js.native
  val value: js.Array[String] = js.native

@js.native
trait StreamHandle extends AsyncSource:
  def send(item: js.Any): Unit = js.native
  def end(): Unit              = js.native
  def dispose(): Unit          = js.native

@js.native
@JSImport("react", JSImport.Namespace)
object React extends js.Object:
  def createElement(`type`: js.Any, props: js.UndefOr[js.Object], children: js.Any*): js.Object = js.native
  val Fragment: js.Object                                                                       = js.native
  def memo(component: js.Any): js.Any                                                           = js.native
  def useLayoutEffect(effect: js.Function0[js.Function0[Unit]], deps: js.Array[js.Any]): Unit   = js.native
  def useRef[A](initial: A): ReactRef[A]                                                        = js.native

@js.native
trait ReactRef[A] extends js.Object:
  var current: A = js.native
