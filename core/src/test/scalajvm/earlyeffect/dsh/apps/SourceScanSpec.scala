package earlyeffect.dsh.apps

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import zio.test.*

/** Walks the checkout on the JVM. Scala.js has no `java.nio.file.Files`, and the scan is a disk read anyway. */
object SourceScanSpec extends ZIOSpecDefault:
  def spec = suite("plugin sources")(
    test("sources do not name a board"):
      val root = Iterator
        .iterate(Paths.get("").toAbsolutePath)(path => Option(path.getParent).getOrElse(path))
        .take(8)
        .find(path => Files.isRegularFile(path.resolve("plugin/config.js")))
      val hits = root.fold(List("plugin/config.js was not found from the working directory"))(SourceScan.hits)
      assertTrue(hits.isEmpty)
  )

  /** The needle is split so this file does not itself match. */
  private object SourceScan:
    private val needle = "to" + "do"
    private val roots  = List(
      "core/src",
      "client/src",
      "host/src",
      "facade/src",
      "plugin/src",
      "counter/src",
      "plugin/config.js",
      "plugin/entry.js",
      "plugin/package.json",
      "README.md",
    )
    private val suffixes = Set(".scala", ".js", ".md", ".yml", ".yaml", ".json")

    def hits(root: Path): List[String] =
      roots.flatMap { rel =>
        Try(entries(root.resolve(rel))).fold(
          err => List(s"$rel: ${err.getClass.getSimpleName}"),
          paths => paths.flatMap(file => lineHits(root, file)),
        )
      }

    private def entries(path: Path): List[Path] =
      if !Files.exists(path) then Nil
      else if Files.isRegularFile(path) then List(path).filter(wanted)
      else
        val listed   = Files.list(path)
        val children =
          try listed.iterator().asScala.toList
          finally listed.close()
        children.flatMap(entries)

    private def wanted(path: Path): Boolean =
      val name = Option(path.getFileName).fold("")(_.toString)
      suffixes.exists(name.endsWith) &&
      !path.iterator().asScala.exists(part => part.toString == "target" || part.toString == "node_modules")

    private def lineHits(root: Path, file: Path): List[String] =
      Try(Files.readString(file, StandardCharsets.UTF_8)) match
        case scala.util.Success(text) =>
          text.linesIterator.zipWithIndex.collect {
            case (line, index) if line.contains(needle) => s"${root.relativize(file)}:${index + 1}"
          }.toList
        case scala.util.Failure(err) => List(s"${root.relativize(file)}: ${err.getClass.getSimpleName}")
  end SourceScan
end SourceScanSpec
