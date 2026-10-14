package com.github.j5ik2o.event.store.adapter.scala.apisurface

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper

import java.lang.reflect.{Method, ParameterizedType, Type, TypeVariable}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** A type expression of surface.json such as `optional<snapshot_read<A>>`. */
final case class Expr(name: String, args: Seq[Expr])

object Expr {
  def parse(text: String): Expr = {
    val t = text.trim
    t.indexOf('<') match {
      case -1 => Expr(t, Seq.empty)
      case i =>
        require(t.endsWith(">"), s"unbalanced type expression: $text")
        Expr(t.substring(0, i), splitTopLevel(t.substring(i + 1, t.length - 1)).map(parse))
    }
  }

  private def splitTopLevel(text: String): Seq[String] = {
    val (parts, last, _) = text.foldLeft((Vector.empty[String], "", 0)) {
      case ((done, current, 0), ',') => (done :+ current, "", 0)
      case ((done, current, depth), '<') => (done, current + '<', depth + 1)
      case ((done, current, depth), '>') => (done, current + '>', depth - 1)
      case ((done, current, depth), c) => (done, current + c, depth)
    }
    parts :+ last
  }
}

object ApiSurfaceFiles {
  private val mapper: JsonMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

  val surfacePath: Path = Paths.get("api-surface", "surface.json")
  val mappingPath: Path = Paths.get("api-surface", "scala.json")

  def read(path: Path): JsonNode = mapper.readTree(Files.readAllBytes(path))
}

/**
 * Checks the compiled public API against surface.json through Java reflection. `overrides` maps a fully qualified class
 * name of scala.json to another one, so a test can substitute a deliberately shifted definition.
 */
final class ApiSurfaceChecker(surface: JsonNode, mapping: JsonNode, overrides: Map[String, String] = Map.empty) {
  private val typeParameters: Map[String, String] = fields(mapping.path("type_parameters")).toMap

  def check(): Seq[String] = checkTypes() ++ checkStores() ++ checkErrorCategories()

  private def checkTypes(): Seq[String] =
    surface.path("types").asScala.toSeq.flatMap { t =>
      val name = t.path("name").asText()
      val entry = mapping.path("types").path(name)
      text(entry.path("class")) match {
        case None => Seq(s"$name: no mapping in scala.json")
        case Some(className) =>
          lookup(className) match {
            case None => Seq(s"$name: class $className not found")
            case Some(clazz) if entry.has("accessors") => checkAccessors(name, t, entry.path("accessors"), clazz)
            case Some(_) => Seq.empty
          }
      }
    }

  private def checkAccessors(name: String, t: JsonNode, accessors: JsonNode, clazz: Class[_]): Seq[String] =
    checkTypeParameters(name, clazz, strings(t.path("type_parameters"))) ++
      t.path("elements").asScala.toSeq.flatMap { element =>
        val elementName = element.path("name").asText()
        text(accessors.path(elementName)) match {
          case None => Seq(s"$name.$elementName: no accessor mapping in scala.json")
          case Some(accessor) =>
            clazz.getMethods.find(m => m.getName == accessor && m.getParameterCount == 0) match {
              case None => Seq(s"$name.$elementName: ${clazz.getSimpleName} has no accessor $accessor()")
              case Some(m) =>
                val expr = Expr.parse(element.path("type").asText())
                if (matches(expr, m.getGenericReturnType)) Seq.empty
                else
                  Seq(
                    s"$name.$elementName: ${clazz.getSimpleName}.$accessor returns ${describe(m.getGenericReturnType)}, want ${want(expr)}",
                  )
            }
        }
      }

  private def checkStores(): Seq[String] = {
    val store = surface.path("store")
    val storeName = store.path("name").asText()
    val entries = mapping.path("stores").asScala.toSeq
    val missing = ApiSurfaceChecker.requiredWraps.filterNot(w => entries.exists(_.path("wrap").asText() == w)).map { w =>
      s"$storeName: scala.json has no store wrapped in $w"
    }
    missing ++ entries.flatMap { entry =>
      val traitName = entry.path("trait").asText()
      lookup(traitName) match {
        case None => Seq(s"$storeName: class $traitName not found")
        case Some(clazz) =>
          checkTypeParameters(storeName, clazz, strings(store.path("type_parameters"))) ++
            store.path("operations").asScala.toSeq.flatMap(op => checkOperation(clazz, entry, op))
      }
    }
  }

  private def checkOperation(clazz: Class[_], store: JsonNode, op: JsonNode): Seq[String] = {
    val opName = op.path("name").asText()
    val inputs = op.path("inputs").asScala.toSeq
    val implicits = strings(store.path("implicit_parameters"))
    text(mapping.path("operations").path(opName)) match {
      case None => Seq(s"$opName: no mapping in scala.json")
      case Some(methodName) =>
        val label = s"$opName: ${clazz.getSimpleName}.$methodName"
        clazz.getMethods.find(m =>
          m.getName == methodName && !m.isSynthetic && m.getParameterCount == inputs.size + implicits.size) match {
          case None =>
            Seq(
              s"$opName: ${clazz.getSimpleName} has no method $methodName with ${inputs.size + implicits.size} parameters")
          case Some(m) => checkSignature(label, m, inputs, implicits, store.path("wrap").asText(), op)
        }
    }
  }

  private def checkSignature(
    label: String,
    m: Method,
    inputs: Seq[JsonNode],
    implicits: Seq[String],
    wrap: String,
    op: JsonNode,
  ): Seq[String] = {
    val inputIssues = inputs.zip(m.getGenericParameterTypes.toSeq).flatMap { case (input, actual) =>
      val expr = Expr.parse(input.path("type").asText())
      if (matches(expr, actual)) Seq.empty
      else Seq(s"$label parameter ${input.path("name").asText()} is ${describe(actual)}, want ${want(expr)}")
    }
    val implicitIssues = implicits.zip(m.getParameterTypes.toSeq.drop(inputs.size)).flatMap { case (expected, actual) =>
      if (actual.getName == expected) Seq.empty
      else Seq(s"$label implicit parameter is ${actual.getName}, want $expected")
    }
    val output = Expr.parse(op.path("output").asText())
    val outputIssues = m.getGenericReturnType match {
      case p: ParameterizedType if p.getRawType.getTypeName == wrap && matches(output, p.getActualTypeArguments.head) =>
        Seq.empty
      case actual => Seq(s"$label returns ${describe(actual)}, want $wrap<${want(output)}>")
    }
    inputIssues ++ implicitIssues ++ outputIssues
  }

  private def checkTypeParameters(label: String, clazz: Class[_], wanted: Seq[String]): Seq[String] = {
    val declared = clazz.getTypeParameters.toSeq
    val wantedNames = wanted.map(n => typeParameters.getOrElse(n, n))
    if (declared.map(_.getName) != wantedNames)
      Seq(
        s"$label: ${clazz.getSimpleName} has type parameters ${declared.map(_.getName).mkString("[", ", ", "]")}, want ${wantedNames
            .mkString("[", ", ", "]")}")
    else
      declared.flatMap { p =>
        val bounds = p.getBounds.toSeq.filterNot(_ == classOf[Object])
        if (bounds.isEmpty) Seq.empty
        else
          Seq(
            s"$label: type parameter ${p.getName} of ${clazz.getSimpleName} is bounded by ${bounds.map(describe).mkString("[", ", ", "]")}; want no bound",
          )
      }
  }

  private def checkErrorCategories(): Seq[String] =
    strings(surface.path("error_categories").path("names")).flatMap { name =>
      if (text(mapping.path("error_categories").path("names").path(name)).isDefined) Seq.empty
      else Seq(s"error_categories.$name: no mapping in scala.json")
    }

  private def matches(expr: Expr, actual: Type): Boolean = expr match {
    case Expr("none", Seq()) =>
      actual match {
        case c: Class[_] => c.getName == jvmName(mappingText("none"))
        case _ => false
      }
    case Expr(n, Seq()) if typeParameters.contains(n) =>
      actual match {
        case v: TypeVariable[_] => v.getName == typeParameters(n)
        case _ => false
      }
    case Expr(n, Seq()) if text(mapping.path("value_types").path(n)).isDefined =>
      actual match {
        case c: Class[_] => c.getName == jvmName(mappingText("value_types", n))
        case _ => false
      }
    case Expr(n, Seq(arg)) if text(mapping.path("containers").path(n)).isDefined =>
      actual match {
        case p: ParameterizedType =>
          p.getRawType.getTypeName == mappingText("containers", n) && matches(arg, p.getActualTypeArguments.head)
        case _ => false
      }
    case Expr(n, args) if text(mapping.path("types").path(n).path("class")).isDefined =>
      actual match {
        case p: ParameterizedType =>
          val actualArgs = p.getActualTypeArguments.toSeq
          simpleName(p.getRawType.getTypeName) == simpleName(mappingText("types", n, "class")) &&
          actualArgs.size == args.size && args.zip(actualArgs).forall { case (e, a) => matches(e, a) }
        case _ => false
      }
    case _ => false
  }

  private def want(expr: Expr): String = expr match {
    case Expr("none", Seq()) => mappingText("none")
    case Expr(n, Seq()) if typeParameters.contains(n) => typeParameters(n)
    case Expr(n, Seq()) if text(mapping.path("value_types").path(n)).isDefined => mappingText("value_types", n)
    case Expr(n, args) if text(mapping.path("containers").path(n)).isDefined =>
      s"${mappingText("containers", n)}<${args.map(want).mkString(", ")}>"
    case Expr(n, args) if text(mapping.path("types").path(n).path("class")).isDefined =>
      s"${simpleName(mappingText("types", n, "class"))}<${args.map(want).mkString(", ")}>"
    case Expr(n, _) => s"$n (no mapping in scala.json)"
  }

  private def describe(t: Type): String = t match {
    case p: ParameterizedType =>
      s"${p.getRawType.getTypeName}<${p.getActualTypeArguments.map(describe).mkString(", ")}>"
    case v: TypeVariable[_] => v.getName
    case other => other.getTypeName
  }

  private def jvmName(scalaName: String): String = scalaName match {
    case "scala.Unit" => "scala.runtime.BoxedUnit"
    case "scala.Long" => "long"
    case other => other
  }

  private def lookup(name: String): Option[Class[_]] =
    Try(Class.forName(overrides.getOrElse(name, name), false, getClass.getClassLoader)).toOption

  private def mappingText(path: String*): String =
    text(path.foldLeft(mapping)(_.path(_)))
      .getOrElse(throw new IllegalStateException(s"scala.json lacks ${path.mkString(".")}"))

  private def simpleName(name: String): String = name.substring(name.lastIndexOf('.') + 1)

  private def text(node: JsonNode): Option[String] =
    if (node.isMissingNode || node.isNull) None else Some(node.asText())

  private def strings(node: JsonNode): Seq[String] = node.asScala.toSeq.map(_.asText())

  private def fields(node: JsonNode): Seq[(String, String)] =
    node.fieldNames().asScala.toSeq.map(k => k -> node.path(k).asText())
}

object ApiSurfaceChecker {

  /** The synchronous store wraps its results in Try, and the asynchronous one in Future. */
  val requiredWraps: Seq[String] = Seq("scala.util.Try", "scala.concurrent.Future")

  def load(overrides: Map[String, String] = Map.empty): ApiSurfaceChecker =
    new ApiSurfaceChecker(
      ApiSurfaceFiles.read(ApiSurfaceFiles.surfacePath),
      ApiSurfaceFiles.read(ApiSurfaceFiles.mappingPath),
      overrides)
}
