package com.azavea.rf.database

import com.azavea.rf.database.Implicits._
import com.azavea.rf.datamodel._

import doobie._, doobie.implicits._
import doobie.postgres._, doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import cats._, cats.data._, cats.effect.IO, cats.implicits._, cats.syntax._
import io.circe._

import com.lonelyplanet.akka.http.extensions.PageRequest

import java.util.UUID

object PlatformDao extends Dao[Platform] {
  val tableName = "platforms"

  val selectF = sql"""
    SELECT
      id, name, public_settings, is_active, default_organization_id, private_settings
    FROM
  """ ++ tableF

  def createF(platform: Platform) = fr"INSERT INTO" ++ tableF ++ fr"""(
        id, name, public_settings , is_active, default_organization_id, private_settings
      )
      VALUES (
        ${platform.id}, ${platform.name}, ${platform.publicSettings}, ${platform.isActive},
        ${platform.defaultOrganizationId}, ${platform.privateSettings}
      )
  """

  def getPlatformById(platformId: UUID) =
    query.filter(platformId).selectOption

  def unsafeGetPlatformById(platformId: UUID) =
    query.filter(platformId).select

  def listPlatforms(page: PageRequest) =
    query.page(page)

  def listMembers(platformId: UUID, page: PageRequest, searchParams: SearchQueryParameters, actingUser: User): ConnectionIO[PaginatedResponse[User.WithGroupRole]] =
    UserGroupRoleDao.listUsersByGroup(GroupType.Platform, platformId, page, searchParams, actingUser) map {
      (usersePage: PaginatedResponse[User.WithGroupRole]) => {
         usersePage.copy(results = usersePage.results map { _.copy(email = "" ) })
      }
    }

  def create(platform: Platform): ConnectionIO[Platform] = {
    createF(platform).update.withUniqueGeneratedKeys[Platform](
      "id", "name", "public_settings", "is_active", "default_organization_id", "private_settings"
    )
  }

  def update(platform: Platform, id: UUID, user: User): ConnectionIO[Int] = {
      (fr"UPDATE" ++ tableF ++ fr"""SET
        name = ${platform.name},
        public_settings = ${platform.publicSettings},
        default_organization_id = ${platform.defaultOrganizationId},
        private_settings = ${platform.privateSettings}
        where id = ${id}
      """).update.run
  }

  def validatePath(platformId: UUID): ConnectionIO[Boolean] =
  (fr"""
    SELECT count(p.id) > 0
    FROM """ ++ tableF ++ fr""" p
    WHERE p.id = ${platformId}
  """).query[Boolean].option.map(_.getOrElse(false))

  def userIsMemberF(user: User, platformId: UUID ) = fr"""
    SELECT (
        SELECT is_superuser
        FROM """ ++ UserDao.tableF ++ fr"""
        WHERE id = ${user.id}
      ) OR (
        SELECT count(id) > 0
        FROM """ ++ UserGroupRoleDao.tableF ++ fr"""
        WHERE
          user_id = ${user.id} AND
          group_type = ${GroupType.Platform.toString}::group_type AND
          group_id = ${platformId} AND
          is_active = true
      )
  """

  def userIsMember(user: User, platformId: UUID): ConnectionIO[Boolean] =
    userIsMemberF(user, platformId).query[Boolean].option.map(_.getOrElse(false))


  def userIsAdminF(user: User, platformId: UUID) = fr"""
      SELECT (
        SELECT is_superuser
        FROM """ ++ UserDao.tableF ++ fr"""
        WHERE id = ${user.id}
      ) OR (
        SELECT count(ugr.id) > 0
        FROM """ ++ UserGroupRoleDao.tableF ++ fr""" ugr
        JOIN """ ++ tableF ++ fr""" p ON ugr.group_id = p.id
        WHERE
          ugr.user_id = ${user.id} AND
          ugr.group_type = ${GroupType.Platform.toString}::group_type AND
          ugr.group_role = ${GroupRole.Admin.toString}::group_role AND
          ugr.group_id = ${platformId} AND
          ugr.is_active = true AND
          p.is_active = true AND
          membership_status = 'APPROVED'
      )
  """

  def userIsAdmin(user: User, platformId: UUID) =
    userIsAdminF(user, platformId).query[Boolean].option.map(_.getOrElse(false))

  def delete(platformId: UUID): ConnectionIO[Int] =
    PlatformDao.query.filter(platformId).delete

  def addUserRole(actingUser: User, subjectId: String, platformId: UUID, userRole: GroupRole): ConnectionIO[UserGroupRole] = {
    val userGroupRoleCreate = UserGroupRole.Create(
      subjectId, GroupType.Platform, platformId, userRole
    )
    UserGroupRoleDao.create(userGroupRoleCreate.toUserGroupRole(actingUser, MembershipStatus.Approved))
  }

  def setUserRole(actingUser: User, subjectId: String, platformId: UUID, userRole: GroupRole):
      ConnectionIO[List[UserGroupRole]] = {
    deactivateUserRoles(actingUser, subjectId, platformId)
      .flatMap(updatedRoles =>
        addUserRole(actingUser, subjectId, platformId, userRole).map((ugr: UserGroupRole) => updatedRoles ++ List(ugr))
      )
  }

  def deactivateUserRoles(actingUser: User, subjectId: String, platformId: UUID): ConnectionIO[List[UserGroupRole]] = {
    val userGroup = UserGroupRole.UserGroup(subjectId, GroupType.Platform, platformId)
    UserGroupRoleDao.deactivateUserGroupRoles(userGroup, actingUser)
  }

  def activatePlatform(platformId: UUID) = {
    (fr"UPDATE" ++ tableF ++ fr"""SET
       is_active = true,
       where id = ${platformId}
      """).update.run
  }

  def deactivatePlatform(platformId: UUID) = {
    (fr"UPDATE" ++ tableF ++ fr"""SET
       is_active = false,
       where id = ${platformId}
      """).update.run
  }

  def organizationIsPublicOrg(organizationId: UUID, platformId: UUID): ConnectionIO[Boolean] = {
    query.filter(platformId).selectOption map {
      platformO => {
        platformO.map( _.defaultOrganizationId == Some(organizationId)).getOrElse(false)
      }
    }
  }

  def getPlatUsersAndProjByConsumerAndSceneID(userIds: List[String], sceneId: UUID): ConnectionIO[List[PlatformWithUsersSceneProjects]] = {
    val userIdsString = "(" ++ userIds.map("'" ++ _.toString() ++ "'" ).mkString(", ") ++ ")"
    val sceneIdString = "'" ++ sceneId.toString ++ "'"
    Fragment.const(
      s"""
        SELECT plat.id AS plat_id, plat.name AS plat_name, u.id AS u_id, u.name AS u_name,
               plat.public_settings AS pub_settings, plat.private_settings AS pri_settings,
               u.email AS email, u.email_notifications AS email_notifications,
               prj.id AS projectId, prj.name AS project_name
        FROM users AS u
          JOIN user_group_roles AS ugr
          ON u.id = ugr.user_id
          JOIN platforms AS plat
          ON ugr.group_id = plat.id
          JOIN projects AS prj
          ON u.id = prj.owner
          JOIN scenes_to_projects AS stp
          ON prj.id = stp.project_id
        WHERE stp.scene_id = ${sceneIdString}
          AND u.id IN ${userIdsString}
      """
    ).query[PlatformWithUsersSceneProjects].to[List]
  }

  def getPlatAndUsersBySceneOwnerId(sceneOwnerIdO: Option[String]): ConnectionIO[Option[PlatformWithSceneOwner]] = {
    sceneOwnerIdO match {
      case Some(sceneOwnerId) => {
        val ownerId = "'" ++ sceneOwnerId ++ "'"
        Fragment.const(
          s"""
            SELECT DISTINCT
              plat.id AS plat_id, plat.name AS plat_name, u.id AS u_id, u.name AS u_name,
              plat.public_settings AS pub_settings, plat.private_settings AS pri_settings,
              u.email AS email, u.email_notifications AS email_notifications
            FROM users AS u
              JOIN user_group_roles AS ugr
              ON u.id = ugr.user_id
              JOIN platforms AS plat
              ON ugr.group_id = plat.id
              JOIN projects AS prj
              ON u.id = prj.owner
              JOIN scenes_to_projects AS stp
              ON prj.id = stp.project_id
            WHERE u.id = ${ownerId}
          """).query[PlatformWithSceneOwner].option
      }
      case _ => Option.empty.pure[ConnectionIO]
    }
  }
}
