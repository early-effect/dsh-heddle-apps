package earlyeffect.dsh.apps

import scala.scalajs.js
import zio.json.ast.Json

/** A zio-json value as the JavaScript value `JSON.parse` would have produced. */
object JsJson:
  def from(json: Json): js.Any =
    json match
      case Json.Null        => null
      case Json.Bool(value) => value
      case Json.Str(value)  => value
      case Json.Num(value)  => value.doubleValue()
      case Json.Arr(items)  => js.Array(items.map(from)*)
      case Json.Obj(fields) =>
        val obj = js.Dictionary.empty[js.Any]
        fields.foreach((key, value) => obj(key) = from(value))
        obj

  /** A cosmokit volatile is `{ get() }` plus a symbol. `JSON.stringify` drops `get` and leaves `{}`. */
  def plain(value: js.Any): js.Any =
    if value == null || js.isUndefined(value) then value
    else if js.Array.isArray(value) then
      val items = value.asInstanceOf[js.Array[js.Any]]
      val copy  = new js.Array[js.Any]()
      items.foreach(item => copy.push(plain(item)))
      copy
    else if js.typeOf(value) == "object" then
      val keys    = js.Object.keys(value.asInstanceOf[js.Object])
      val read    = value.asInstanceOf[js.Dynamic].selectDynamic("get")
      val onlyGet = keys.length == 1 && js.Array.from(keys).forall(_ == "get")
      if onlyGet && js.typeOf(read) == "function" then plain(read.asInstanceOf[js.Function0[js.Any]]())
      else
        val copy = js.Dictionary.empty[js.Any]
        keys.foreach(key => copy(key) = plain(value.asInstanceOf[js.Dynamic].selectDynamic(key)))
        copy
    else value
end JsJson
