package earlyeffect.dsh.apps

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** One App server the host row connects to. */
enum Endpoint:
  case Http(name: String, url: String)
  case Stdio(name: String, command: String, args: Chunk[String], cwd: Option[String] = None)

enum RowError(val message: String):
  case Name(error: NameError) extends RowError(error.message)
  case Transport              extends RowError("transport is \"http\" or \"stdio\"")
  case Url                    extends RowError("an http server has a non-empty url")
  case Command                extends RowError("a stdio server has a non-empty command")
  case Args                   extends RowError("stdio args is an array of strings")
  case Cwd                    extends RowError("stdio cwd is a non-empty path")

enum ConfigError(val message: String):
  case NotObject                         extends ConfigError("config is a json object")
  case Servers                           extends ConfigError("servers is an array")
  case Row(index: Int, reason: RowError) extends ConfigError(s"servers[$index]: ${reason.message}")

object PluginConfig:
  def parse(text: String): Either[ConfigError, Chunk[Endpoint]] =
    text.fromJson[Json].left.map(_ => ConfigError.NotObject).flatMap {
      case obj: Json.Obj =>
        obj.fields.collectFirst { case ("servers", Json.Arr(rows)) => rows } match
          case None       => Left(ConfigError.Servers)
          case Some(rows) =>
            rows.zipWithIndex.foldLeft[Either[ConfigError, Chunk[Endpoint]]](Right(Chunk.empty)) {
              case (Left(err), _)             => Left(err)
              case (Right(acc), (row, index)) => one(row, index).map(acc :+ _)
            }
      case _ => Left(ConfigError.NotObject)
    }

  private def one(row: Json, index: Int): Either[ConfigError, Endpoint] =
    row match
      case obj: Json.Obj =>
        val transport = obj.fields.collectFirst { case ("transport", Json.Str(s)) => s }
        val parsed    = transport match
          case Some("http")  => http(obj)
          case Some("stdio") => stdio(obj)
          case _             => Left(RowError.Transport)
        parsed.left.map(ConfigError.Row(index, _))
      case _ => Left(ConfigError.Row(index, RowError.Transport))

  private def http(obj: Json.Obj): Either[RowError, Endpoint] =
    for
      name <- nameOf(obj)
      url  <- nonEmpty(obj, "url", RowError.Url)
    yield Endpoint.Http(name, url)

  private def stdio(obj: Json.Obj): Either[RowError, Endpoint] =
    for
      name    <- nameOf(obj)
      command <- nonEmpty(obj, "command", RowError.Command)
      args    <- argsOf(obj)
      cwd     <- cwdOf(obj)
    yield Endpoint.Stdio(name, command, args, cwd)

  private def nameOf(obj: Json.Obj): Either[RowError, String] =
    nonEmpty(obj, "name", RowError.Name(NameError.Empty)).flatMap(raw => DshServer.from(raw).left.map(RowError.Name(_)))

  private def nonEmpty(obj: Json.Obj, field: String, ifMissing: RowError): Either[RowError, String] =
    obj.fields.collectFirst { case (`field`, Json.Str(s)) if s.nonEmpty => s }.toRight(ifMissing)

  private def argsOf(obj: Json.Obj): Either[RowError, Chunk[String]] =
    obj.fields.collectFirst { case ("args", value) => value } match
      case None                  => Right(Chunk.empty)
      case Some(Json.Arr(items)) =>
        items.foldLeft[Either[RowError, Chunk[String]]](Right(Chunk.empty)) {
          case (Left(err), _)            => Left(err)
          case (Right(acc), Json.Str(s)) => Right(acc :+ s)
          case (Right(_), _)             => Left(RowError.Args)
        }
      case Some(_) => Left(RowError.Args)

  private def cwdOf(obj: Json.Obj): Either[RowError, Option[String]] =
    obj.fields.collectFirst { case ("cwd", value) => value } match
      case None                                  => Right(None)
      case Some(Json.Str(path)) if path.nonEmpty => Right(Some(path))
      case Some(_)                               => Left(RowError.Cwd)
end PluginConfig
