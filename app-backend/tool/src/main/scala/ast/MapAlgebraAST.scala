package com.azavea.rf.tool.ast

import geotrellis.vector.MultiPolygon
import java.util.UUID

import io.circe.generic.JsonCodec


/** The ur-type for a recursive representation of MapAlgebra operations */
sealed trait MapAlgebraAST extends Product with Serializable {
  def id: UUID
  def args: List[MapAlgebraAST]
  def metadata: Option[NodeMetadata]
  def find(id: UUID): Option[MapAlgebraAST]
  def sources: Seq[MapAlgebraAST.MapAlgebraLeaf]
}

object MapAlgebraAST {
  /** Map Algebra operations (nodes in this tree) */
  sealed trait Operation extends MapAlgebraAST with Serializable {

    val symbol: String

    @SuppressWarnings(Array("TraversableHead"))
    def find(id: UUID): Option[MapAlgebraAST] =
      if (this.id == id)
        Some(this)
      else {
        val matches = args.flatMap(_.find(id))
        matches.headOption
      }

    def sources: Seq[MapAlgebraAST.MapAlgebraLeaf] = args.flatMap(_.sources).distinct
  }

  /** Operations which should only have one argument. */
  sealed trait UnaryOp extends Operation with Serializable

  case class Addition(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata])
      extends Operation { val symbol = "+" }

  case class Subtraction(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata])
      extends Operation { val symbol = "-" }

  case class Multiplication(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata])
      extends Operation { val symbol = "*" }

  case class Division(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata])
      extends Operation { val symbol = "/" }

  case class Masking(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata], mask: MultiPolygon)
      extends UnaryOp { val symbol = "mask" }

  case class Classification(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata], classMap: ClassMap)
      extends UnaryOp { val symbol = "classify" }

  case class Max(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata])
      extends Operation { val symbol = "max" }

  case class Min(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata])
      extends Operation { val symbol = "min" }

  case class Linear(args: List[MapAlgebraAST], id: UUID, metadata: Option[NodeMetadata], f: Double => Double)
    extends UnaryOp { val symbol = "linear" }

  sealed trait MapAlgebraLeaf extends MapAlgebraAST {
    val `type`: String

    def args: List[MapAlgebraAST] = List.empty

    def find(id: UUID): Option[MapAlgebraAST] =
      if (this.id == id) Some(this)
      else None
  }

  @JsonCodec
  case class Constant(id: UUID, constant: Double, metadata: Option[NodeMetadata]) extends MapAlgebraLeaf {
    val `type` = "const"

    def sources: Seq[MapAlgebraAST.MapAlgebraLeaf] = List()
  }

  /** Map Algebra sources */
  @JsonCodec
  case class Source(id: UUID, metadata: Option[NodeMetadata]) extends MapAlgebraLeaf {
    val `type` = "src"

    def sources: Seq[MapAlgebraAST.MapAlgebraLeaf] = List(this)
  }

  object Source {
    def empty: Source = Source(UUID.randomUUID(), None)
  }

  /** TODO: Add other source types (or treat of them as hyperparameters - e.g. ClassMap, above) */

}
