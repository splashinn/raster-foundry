package com.azavea.rf.datamodel

import java.sql.Timestamp
import java.time.Instant
import java.util.{Date, UUID}

import com.azavea.rf.bridge._
import geotrellis.slick.Projected
import geotrellis.vector.MultiPolygon
import io.circe._
import io.circe.generic.JsonCodec

// --- //

/** A Project's Area of Interest.
  * This represents an area on the map (a `MultiPolygon`) which a user has
  * set filters for.  If a new Scene entering the system passes these filters,
  * the Scene will be added to the user's Project in a "pending" state. If the
  * user then accepts a "pending" Scene, it will be added to their project.
  */
@JsonCodec
case class AOI(
  /* Database fields */
  id: UUID,
  createdAt: Timestamp,
  modifiedAt: Timestamp,
  createdBy: String,
  modifiedBy: String,
  owner: String,

  /* Unique fields */
  area: Projected[MultiPolygon],
  filters: Json,

  isActive: Boolean = true,
  startTime: Timestamp,
  approvalRequired: Boolean,
  projectId: UUID
)

object AOI {

  def tupled = (AOI.apply _).tupled

  def create = Create.apply _

  @JsonCodec
  case class Create(
    area: Projected[MultiPolygon],
    filters: Json,
    owner: Option[String],
    isActive: Boolean = true,
    startTime: Timestamp = Timestamp.from(Instant.now),
    approvalRequired: Boolean = true) extends OwnerCheck {
    def toAOI(projectId: UUID, user: User): AOI = {
      val now = new Timestamp((new Date()).getTime)

      val ownerId = checkOwner(user, this.owner)

      AOI(
        UUID.randomUUID, now, now,
        user.id, user.id, ownerId, area, filters, isActive,
        startTime, approvalRequired, projectId
      )
    }
  }
}
