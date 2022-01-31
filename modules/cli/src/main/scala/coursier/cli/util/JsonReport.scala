package coursier.cli.util

import java.io.File
import java.util.Objects

import cats.{Eq, Eval, Show}
import cats.data.Chain
import cats.free.Cofree
import cats.syntax.foldable._
import cats.syntax.traverse._
import cats.syntax.show._

import coursier.core._
import coursier.util.Artifact

import scala.collection.mutable
import scala.collection.immutable.SortedSet

import argonaut._
import Argonaut._

/** Lookup table for files and artifacts to print in the JsonReport.
  */
final case class JsonPrintRequirement(
  fileByArtifact: Map[String, File],
  depToArtifacts: Map[Dependency, Vector[(Publication, Artifact)]]
)

/** Represents a resolved dependency's artifact in the JsonReport.
  * @param coord
  *   String representation of the artifact's maven coordinate.
  * @param file
  *   The path to the file for the artifact.
  * @param dependencies
  *   The dependencies of the artifact.
  */
final case class DepNode(
  coord: String,
  file: Option[String],
  directDependencies: Set[String],
  dependencies: Set[String],
  exclusions: Set[String] = Set.empty
)

final case class ReportNode(
  conflict_resolution: Map[String, String],
  dependencies: Vector[DepNode],
  version: String
)

/** FORMAT_VERSION_NUMBER: Version number for identifying the export file format output. This
  * version number should change when there is a change to the output format.
  *
  * Major Version 1.x.x : Increment this field when there is a major format change Minor Version
  * x.1.x : Increment this field when there is a minor change that breaks backward compatibility for
  * an existing field or a field is removed. Patch version x.x.1 : Increment this field when a minor
  * format change that just adds information that an application can safely ignore.
  *
  * Note format changes in cli/README.md and update the Changelog section.
  */
object ReportNode {
  import argonaut.ArgonautShapeless._
  implicit val encodeJson = EncodeJson.of[ReportNode]
  implicit val decodeJson = DecodeJson.of[ReportNode]
  val version             = "0.1.0"
}

object JsonReport {

  private val printer = PrettyParams.nospace.copy(preserveOrder = true)

  // A dependency tree its a (A => Seq[A]) mapping and therefore it forms a Cofree[Chain, T]
  private type DependencyTree[A] = Cofree[Chain, A]
  private object DependencyTree {
    // Collapse node builds the list of dependencies by reaching to the leave first
    // and then building back the list.
    // Using Eval here as it will give us a stack-safe flatMap operation
    private def collapseNode[A: Show](node: DependencyTree[A]): Eval[Chain[String]] =
      node.foldMapM(child => Eval.now(Chain.one(child.show)))

    // Builds a map of flattened dependencies starting at this element
    // The implementation makes use of Cofree[List, T] which is a foldable co-monad
    // and because of that, we can collapse it at each of its nodes and aggregate the results
    private def transitiveOf[A: Eq: Show](
      elem: A,
      fetchChildren: A => Seq[A]
    ): Eval[Map[A, Chain[String]]] = {
      // Create the DepTree structure by unfolding it starting at the element given
      val zipper                     = TreeZipper.of(elem, fetchChildren)
      val depTree: DependencyTree[A] = Cofree.ana(zipper)(_.children, _.focus)

      // Fold the tail of the root into a chain of dependencies
      // and them map the root element to the dependencies
      // Using `Eval` here to do the fold so we convert the recursive operation into a stack-safe loop
      depTree.tail
        .flatMap(_.foldMapM(collapseNode[A]))
        .map(deps => Map(depTree.head -> deps))
    }

    def flatten[A](
      roots: Vector[A],
      fetchChildren: A => Seq[A],
      reconciledVersionStr: A => String
    ): Map[A, Set[String]] = {
      implicit val nodeShow: Show[A] = Show.show(reconciledVersionStr)
      implicit val nodeEq: Eq[A]     = Eq.by(_.show)

      Chain.fromSeq(roots)
        .traverse(transitiveOf(_, fetchChildren))
        .value
        .fold
        .mapValues(_.iterator.to[SortedSet])
    }
  }

  def apply[T](roots: Vector[T], conflictResolutionForRoots: Map[String, String])(
    children: T => Seq[T],
    reconciledVersionStr: T => String,
    requestedVersionStr: T => String,
    getFile: T => Option[String],
    exclusions: T => Set[String]
  ): String = {

    // Addresses the corner case in which any given library may list itself among its dependencies
    // See: https://github.com/coursier/coursier/issues/2316
    def childrenOrEmpty(elem: T): Chain[T] = {
      val elemId = reconciledVersionStr(elem)
      Chain.fromSeq(children(elem).filterNot(i => reconciledVersionStr(i) == elemId))
    }

    val depToTransitiveDeps =
      DependencyTree.flatten(roots, children, reconciledVersionStr)

    def flattenedDeps(elem: T): Set[String] =
      depToTransitiveDeps.getOrElse(elem, Set.empty[String])

    val rootDeps: Vector[DepNode] = roots.map { r =>
      DepNode(
        reconciledVersionStr(r),
        getFile(r),
        childrenOrEmpty(r).iterator.map(reconciledVersionStr(_)).to[SortedSet],
        flattenedDeps(r),
        exclusions(r)
      )
    }

    val report = ReportNode(
      conflictResolutionForRoots,
      rootDeps.sortBy(_.coord),
      ReportNode.version
    )
    printer.pretty(report.asJson)
  }

}

final case class JsonElem(
  dep: Dependency,
  artifacts: Seq[(Dependency, Artifact)] = Seq(),
  jsonPrintRequirement: Option[JsonPrintRequirement],
  resolution: Resolution,
  colors: Boolean,
  printExclusions: Boolean,
  excluded: Boolean,
  overrideClassifiers: Set[Classifier]
) {

  // This is used to printing json output
  // Option of the file path
  lazy val downloadedFile: Option[String] =
    jsonPrintRequirement.flatMap(req =>
      req.depToArtifacts.getOrElse(dep, Seq()).view
        .filter(_._1.classifier == dep.attributes.classifier)
        .map(x => req.fileByArtifact.get(x._2.url))
        .filter(_.isDefined)
        .filter(_.nonEmpty)
        .map(_.get.getPath)
        .headOption
    )

  lazy val reconciledVersion: String = resolution.reconciledVersions
    .getOrElse(dep.module, dep.version)

  // These are used to printing json output
  val reconciledVersionStr = s"${dep.mavenPrefix}:$reconciledVersion"
  val requestedVersionStr  = s"${dep.module}:${dep.version}"

  lazy val exclusions: Set[String] = dep.exclusions.map {
    case (org, name) =>
      s"${org.value}:${name.value}"
  }

  lazy val children: Vector[JsonElem] =
    if (excluded) Vector.empty
    else {
      val dependencies = resolution.dependenciesOf(
        dep,
        withRetainedVersions = false
      ).sortBy { trDep =>
        (trDep.module.organization, trDep.module.name, trDep.version)
      }.map { d =>
        if (overrideClassifiers.contains(dep.attributes.classifier))
          d.withAttributes(d.attributes.withClassifier(dep.attributes.classifier))
        else
          d
      }

      def calculateExclusions = resolution
        .dependenciesOf(
          dep.withExclusions(Set.empty),
          withRetainedVersions = false
        )
        .view
        .sortBy { trDep =>
          (trDep.module.organization, trDep.module.name, trDep.version)
        }
        .map(_.moduleVersion)
        .filterNot(dependencies.map(_.moduleVersion).toSet).map {
          case (mod, ver) =>
            JsonElem(
              Dependency(mod, ver)
                .withConfiguration(Configuration.empty)
                .withExclusions(Set.empty[(Organization, ModuleName)])
                .withAttributes(Attributes.empty)
                .withOptional(false)
                .withTransitive(false),
              artifacts,
              jsonPrintRequirement,
              resolution,
              colors,
              printExclusions,
              excluded = true,
              overrideClassifiers = overrideClassifiers
            )
        }

      val dependencyElems = mutable.ListBuffer.empty[JsonElem]
      val it              = dependencies.iterator
      while (it.hasNext) {
        val dep = it.next()
        val elem = JsonElem(
          dep,
          artifacts,
          jsonPrintRequirement,
          resolution,
          colors,
          printExclusions,
          excluded = false,
          overrideClassifiers = overrideClassifiers
        )
        dependencyElems += elem
      }
      dependencyElems ++ (if (printExclusions) calculateExclusions else Nil)
      dependencyElems.toVector
    }

  /** Override the hashcode to explicitly exclude `children`, because children will result in
    * recursive hash on children's children, causing performance issue. Hash collision should be
    * rare, but when that happens, the default equality check should take of the recursive aspect of
    * `children`.
    */
  override def hashCode(): Int =
    Objects.hash(dep, requestedVersionStr, reconciledVersion, downloadedFile)
}
