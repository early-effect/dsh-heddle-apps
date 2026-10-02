package earlyeffect.dsh.apps.counter

import heddle.*
import heddle.apps.browser.counter.{Count, Counter}
import heddle.mcp.{Mcp, McpBuildError}
import heddle.mcp.apps.{AppBuildError, UiDocument, withApp}
import zio.*

/** Why the counter process did not start. */
enum CounterBoot(val message: String):
  case View(detail: String) extends CounterBoot(s"counter view is not on the classpath ($detail)")
  case Mcp(errors: NonEmptyChunk[McpBuildError]) extends CounterBoot(errors.map(_.message).mkString("; "))
  case App(errors: NonEmptyChunk[AppBuildError]) extends CounterBoot(errors.map(_.message).mkString("; "))
  case Pipe(detail: String) extends CounterBoot(s"stdio failed ($detail)")

/** The browser suite's counter, as one MCP server. `inc` stays app-only. `show_counter` is what a model calls. */
object CounterApp:
  def open(view: String): IO[CounterBoot, Mcp[Any]] =
    for
      count <- Ref.make(0)
      api = Api("Counter", "0.0.0")
        .job(Counter.show)(_ => count.get.map(Count(_)))
        .resource(Counter.inc)(_ => count.updateAndGet(_ + 1).map(Count(_)))
      mcp <- ZIO.fromEither(Mcp.from(api).left.map(CounterBoot.Mcp(_)))
      app <- ZIO.fromEither(mcp.withApp(Counter.shed, UiDocument("Counter", view)).left.map(CounterBoot.App(_)))
    yield app

  def readView: IO[CounterBoot, String] =
    ZIO
      .attemptBlocking {
        val source = scala.io.Source.fromResource("counter-view.js")
        try source.mkString
        finally source.close()
      }
      .mapError(thrown => CounterBoot.View(thrown.getClass.getSimpleName))
end CounterApp
