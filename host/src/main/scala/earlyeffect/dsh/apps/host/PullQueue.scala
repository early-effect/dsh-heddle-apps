package earlyeffect.dsh.apps.host

import scala.scalajs.js
import scala.scalajs.js.annotation.JSName

/** One consumer's async iterator. The two lists are the queue: JS calls `next` on a single thread, so they need no lock. */
final class PullQueue(onClose: () => Unit):
  private var incoming: List[js.Any] = Nil
  private var outgoing: List[js.Any] = Nil
  private var waiters: List[Waiter] = Nil
  private var terminal: Option[Either[js.Any, Unit]] = None
  private var notified = false

  def offer(value: js.Any): Unit =
    if terminal.isEmpty then
      incoming = value :: incoming
      wake()

  def fail(error: js.Any): Unit = close(Left(error))

  def end(): Unit = close(Right(()))

  val iterable: AsyncPull = new AsyncPull(this)

  private[host] def take(resolve: js.Function1[js.Object, Unit], reject: js.Function1[js.Any, Unit]): Unit =
    dequeue match
      case Some(value) => resolve(new Yielded(value))
      case None =>
        terminal match
          case Some(Left(error)) => reject(error)
          case Some(Right(_)) => resolve(new Finished)
          case None => waiters = Waiter(resolve, reject) :: waiters

  private def close(result: Either[js.Any, Unit]): Unit =
    if terminal.isEmpty then
      terminal = Some(result)
      wake()
      if !notified then
        notified = true
        onClose()

  private def wake(): Unit =
    waiters match
      case waiter :: rest =>
        dequeue match
          case Some(value) =>
            waiters = rest
            waiter.resolve(new Yielded(value))
            wake()
          case None =>
            terminal match
              case Some(Left(error)) =>
                waiters = rest
                waiter.reject(error)
                wake()
              case Some(Right(_)) =>
                waiters = rest
                waiter.resolve(new Finished)
                wake()
              case None => ()
      case Nil => ()

  private def dequeue: Option[js.Any] =
    outgoing match
      case head :: tail =>
        outgoing = tail
        Some(head)
      case Nil =>
        incoming.reverse match
          case head :: tail =>
            incoming = Nil
            outgoing = tail
            Some(head)
          case Nil => None

  private final case class Waiter(resolve: js.Function1[js.Object, Unit], reject: js.Function1[js.Any, Unit])
end PullQueue

final class AsyncPull(queue: PullQueue) extends js.Object:
  @JSName(js.Symbol.asyncIterator)
  def asyncIterator(): AsyncPull = this

  def next(): js.Promise[js.Object] =
    new js.Promise[js.Object]((resolve, reject) =>
      queue.take(
        (value: js.Object) =>
          resolve(value)
          (),
        (error: js.Any) =>
          reject(error)
          (),
      )
    )

final class Yielded(val value: js.Any) extends js.Object:
  val done: Boolean = false

final class Finished extends js.Object:
  val done: Boolean = true
