package earlyeffect.dsh.apps.host

import scala.scalajs.js
import zio.{Task, Unsafe, ZIO}

/** A JS promise as a task. The failure is the thrown value, mapped by the caller. */
object JsTask:
  def apply[A](promise: => js.Promise[A]): Task[A] =
    ZIO.async { callback =>
      promise.`then`[Unit](
        (value: A) => callback(ZIO.succeed(value)),
        (error: Any) => callback(ZIO.fail(js.JavaScriptException(error))),
      )
    }

/** A promise completed from ZIO. The executor runs synchronously, so the callbacks exist before `ok` or `fail`. */
final class JsGate[A]:
  private var succeedFn: js.UndefOr[js.Function1[A, Unit]] = js.undefined
  private var rejectFn: js.UndefOr[js.Function1[Any, Unit]] = js.undefined

  val promise: js.Promise[A] =
    new js.Promise[A]((resolve, reject) =>
      succeedFn = (value: A) =>
        resolve(value)
        ()
      rejectFn = (error: Any) =>
        reject(error)
        ()
    )

  def ok(value: A): Unit =
    succeedFn.foreach(_(value))

  def fail(error: Any): Unit =
    rejectFn.foreach(_(error))

/** Forks at the JS edge. The fiber is interrupted from the plugin's disposer; nothing here calls `unsafe.run`. */
object Edge:
  def fork(effect: zio.UIO[Unit]): zio.Fiber.Runtime[Nothing, Unit] =
    Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.fork(effect)
    }
