# dsh-heddle-apps

A dsh plugin that hosts MCP Apps. It is not tied to one server.

## Add a server

Open Plugins, choose this plugin, and add a row.

HTTP is one process, shared by every client of that server. The URL is the server's MCP endpoint. `http://127.0.0.1:8080/mcp` is only an example of the shape.

Stdio is the server's own process. Give it a command, arguments, and an optional working directory.

Save is the only write. Leaving the page drops the draft. A name is letters, digits, `_`, and `-`, at most 32 characters.

## What a call looks like

The question is the tool's summary and the server name. The arguments are labeled under it. The answers are Allow once, Allow for this session, and Don't allow. Allow once starts focused. Don't allow replaces the question with "Not allowed."

The frame title is the resource title the server declared. While it opens, the dock says "Opening". A tool that has a view is the frame. A tool with no view shows its result, labeled Result.

`resources/subscribe` is offered experimentally, and only when the host was built with it on. A view that asks for no network gets no network.

## Build

`sbt heddlePlugin/stagePlugin` writes `plugin/lib/index.js` and `plugin/client.js`. dsh loads this directory.
