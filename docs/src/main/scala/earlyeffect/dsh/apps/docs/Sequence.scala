package earlyeffect.dsh.apps.docs

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import earlyeffect.dsh.apps.PluginConfig
import mermoid.{LayoutConfig, Mermaid, RenderConfig, SequenceConfig, SvgNode, SvgRenderer}
import specular.*
import zio.test.assertTrue

/** Plugin, the HTTP or stdio server, and the MCP app frame. No live server: the picture is the page. */
object Sequence extends DocSpec:

  private val call =
    Mermaid("""sequenceDiagram
              |    participant Plugin
              |    participant Server as HTTP or stdio
              |    participant Frame
              |    Plugin->>Server: http or<br/>stdio
              |    Plugin->>Server: listTools
              |    Server-->>Plugin: tools
              |    Plugin->>Server: callTool
              |    Server-->>Plugin: result
              |    Frame->>Plugin: open
              |    Plugin-->>Frame: serve
              |""".stripMargin)

  /** Three columns. Gaps stay tight so a 390px column can scale the picture without cropping it. */
  private val shown =
    RenderConfig(
      layout = LayoutConfig(padding = 12, fontSize = 15, edgeLabelFontSize = 13, lineHeight = 18),
      sequence = SequenceConfig(
        actorMinWidth = 64,
        actorPadH = 8,
        actorHeight = 36,
        columnGap = 24,
        rowPitch = 36,
        headerGap = 10,
        footerGap = 14,
      ),
    )

  def doc = page("Sequence")(
    illustration(diagram(SvgRenderer.renderTree(call.diagram, shown))).assert { ui =>
      val text = ui.toString
      assertTrue(
        text.contains("Plugin"),
        text.contains("HTTP or stdio"),
        text.contains("Frame"),
        text.contains("http or"),
        text.contains("stdio"),
        text.contains("listTools"),
        text.contains("callTool"),
        text.contains("result"),
        text.contains("open"),
        text.contains("serve"),
      )
    },
    md"""
`Plugin` opens each saved server. HTTP is one shared process, `McpClient.http`. Stdio is that server's own process, `McpStdio.spawn`. This plugin is not that server.

A tool that declares a view is registered under a public name. `WebClient` asks for those names and claims the view. The call is `callTool`. The frame is `Frame.follow`: it sends `open`, and `RemoteFrame` serves the mount.
""",
    section("Install")(
      md"""
This is not a library coordinate.

`sbt heddlePlugin/stagePlugin` writes `plugin/lib/index.js` and `plugin/client.js`. dsh loads that directory.

Open Plugins, choose this plugin, and add a row. For HTTP, the URL is the server's MCP endpoint. For stdio, give a command, arguments, and an optional working directory. Save is the only write. Leaving the page drops the draft. A name is letters, digits, `_`, and `-`, at most 32 characters.
"""
    ),
    section("PluginConfig.parse")(
      md"""
Save stores rows. `PluginConfig.parse` is what the card and the host both accept: `http` with a url, or `stdio` with a command.
""",
      cite(PluginConfig.parse(_)),
    ),
  )

  /** Specular has no raw-HTML node. The SVG tree is the illustration, and it shrinks with the column. */
  private def diagram(node: SvgNode): UI[Any] =
    svgUi(node) match
      case UI.Element(tag, attrs, children) if tag == "svg" =>
        UI.Element(
          tag,
          attrs :+ Attr.StaticAttr("style", AttrValue.Str("max-width:100%;height:auto")),
          children,
        )
      case other => other

  private def svgUi(node: SvgNode): UI[Any] = node match
    case SvgNode.Text(value)  => UI.Text(value)
    case SvgNode.Raw(content) => UI.Text(content)
    case SvgNode.Element(tag, attrs, children) =>
      UI.Element(
        tag,
        attrs.map((name, value) => Attr.StaticAttr(name, AttrValue.Str(value))).toVector,
        children.map(svgUi).toVector,
      )
end Sequence
