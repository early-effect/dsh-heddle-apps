package earlyeffect.dsh.apps.counter

import zio.*

/** Stdio MCP for `dsh web`. Nothing is written to stdout except protocol lines. */
object CounterServer extends ZIOAppDefault:
  def run =
    (for
      view <- CounterApp.readView
      app <- CounterApp.open(view)
      _ <- app.stdio().mapError(thrown => CounterBoot.Pipe(thrown.getClass.getSimpleName))
    yield ()).provideLayer(Runtime.removeDefaultLoggers)
end CounterServer
