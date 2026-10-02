#!/bin/sh
# Launch the counter MCP server on stdio. stdout is the protocol; do not echo.
set -eu
here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
exec java -cp "$(cat "$here/target/classpath")" earlyeffect.dsh.apps.counter.CounterServer
