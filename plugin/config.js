import Schema from "@deepseek-ai/schemastery"

const name = Schema.string().required().description("Letters, digits, underscore, and hyphen. At most 32.")

const http = Schema.object({
  name,
  transport: Schema.const("http").required().description("One process, shared by every client of that server."),
  url: Schema.string().required().description("Example: http://127.0.0.1:8080/mcp"),
})

const stdio = Schema.object({
  name,
  transport: Schema.const("stdio").required().description("Its own process."),
  command: Schema.string().required(),
  args: Schema.array(Schema.string()).default([]),
  cwd: Schema.string().description("Optional. An empty directory is not a path."),
})

// No transform callback. dsh serializes Config and evaluates it in the page, which
// cannot see parseServers. A callback that throws there leaves the form on "loading".
// The card and the host both refuse a bad row through PluginConfig.parse.
export const Config = Schema.object({
  servers: Schema.array(Schema.union([http, stdio])).default([]).volatile(),
})
