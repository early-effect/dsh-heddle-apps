package earlyeffect.dsh.apps.host

import heddle.client.ClientError
import heddle.mcp.apps.host.{MountRefusal, PinFileError}
import heddle.mcp.client.McpError

/** Why the host row refused to boot, to register a call, or to open a frame. */
enum HostFailure(val message: String):
  case NoHome                              extends HostFailure("DSH_HOME or HOME is required for the pin file")
  case Pins(error: PinFileError)           extends HostFailure(error.message)
  case Client(error: ClientError)          extends HostFailure(error.message)
  case Tool(name: String, error: McpError) extends HostFailure(s"$name: ${error.message}")
  case Mount(refusal: MountRefusal)        extends HostFailure(refusal.message)
  case Arguments                           extends HostFailure("tool arguments are a json object")
  case MissingMount(callId: String)        extends HostFailure(s"no mounted view for $callId")
  case Stopped                             extends HostFailure("the host row stopped")
  case Protocol                            extends HostFailure("frame event")
end HostFailure
