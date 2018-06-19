package com.azavea.rf.api.user

import java.net.URLDecoder

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.lonelyplanet.akka.http.extensions.PaginationDirectives
import com.dropbox.core.{DbxAppInfo, DbxRequestConfig, DbxWebAuth}
import io.circe._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import com.typesafe.scalalogging.LazyLogging

import doobie._
import doobie.implicits._
import doobie.Fragments.in
import doobie.postgres._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor

import com.azavea.rf.common.{Authentication, CommonHandlers, UserErrorHandler}
import com.azavea.rf.database._
import com.azavea.rf.database.filter.Filterables._
import com.azavea.rf.datamodel._
import com.azavea.rf.api.utils.queryparams.QueryParametersCommon


/**
  * Routes for users
  */
trait UserRoutes extends Authentication
    with PaginationDirectives
    with CommonHandlers
    with UserErrorHandler
    with QueryParametersCommon
    with LazyLogging {

  implicit val xa: Transactor[IO]

  val userRoutes: Route = handleExceptions(userExceptionHandler) {
    pathPrefix("me") {
      pathPrefix("teams") {
        pathEndOrSingleSlash {
          get { getUserTeams }
        }
      } ~
      pathPrefix("roles") {
        get { getUserRoles }
      } ~
      pathEndOrSingleSlash {
        get { getAuth0User } ~
          patch { updateAuth0User } ~
          put { updateOwnUser }
      }
    }  ~
    pathPrefix("dropbox-setup") {
      pathEndOrSingleSlash {
        post { getDropboxAccessToken }
      }
    } ~
    pathPrefix("search") {
      pathEndOrSingleSlash {
        get { searchUsers }
      }
    } ~
    pathPrefix(Segment) { authIdEncoded =>
      pathEndOrSingleSlash {
        get { getUserByEncodedAuthId(authIdEncoded) } ~
        put { updateUserByEncodedAuthId(authIdEncoded) }
      }
    }
  }

  def updateOwnUser: Route = authenticate { user =>
    entity(as[User]) { updatedUser =>
      onSuccess(UserDao.storePlanetAccessToken(user, updatedUser).transact(xa).unsafeToFuture()) {
        completeSingleOrNotFound
      }
    }
  }

  def getAuth0User: Route = authenticate { user =>
    complete {
      Auth0UserService.getAuth0User(user.id)
    }
  }

  def updateAuth0User: Route = authenticate {
    user =>
    entity(as[Auth0UserUpdate]) { userUpdate =>
      complete {
        Auth0UserService.updateAuth0User(user.id, userUpdate)
      }
    }
  }

  def getDropboxAccessToken: Route = authenticate {
    user =>
    entity(as[DropboxAuthRequest]) { dbxAuthRequest =>
      val redirectURI = dbxAuthRequest.redirectURI
      val (dbxKey, dbxSecret) =
        (sys.env.get("DROPBOX_KEY"), sys.env.get("DROPBOX_SECRET")) match {
          case (Some(key), Some(secret)) => (key, secret)
          case _ => throw new RuntimeException("App dropbox credentials must be configured")
        }
      val dbxConfig = new DbxRequestConfig("raster-foundry-authorizer")
      val appInfo = new DbxAppInfo(dbxKey, dbxSecret)
      val webAuth = new DbxWebAuth(dbxConfig, appInfo)
      val session = new DummySessionStore()
      val queryParams = Map[String, Array[String]](
        "code" -> Array(dbxAuthRequest.authorizationCode),
        "state" -> Array(session.get)
      ).asJava
      val authFinish = webAuth.finishFromRedirect(
        dbxAuthRequest.redirectURI, session, queryParams
      )
      logger.debug("Auth finish from Dropbox successful")
      complete(
        UserDao.storeDropboxAccessToken(user.id, Credential.fromString(authFinish.getAccessToken)).transact(xa).unsafeToFuture()
      )
    }
  }

  def getUserByEncodedAuthId(authIdEncoded: String): Route = authenticate { user =>
    rejectEmptyResponse {
      val authId = URLDecoder.decode(authIdEncoded, "US_ASCII")
      if (user.id == authId) {
        complete(UserDao.unsafeGetUserById(authId).transact(xa).unsafeToFuture())
      } else {
        complete(StatusCodes.NotFound)
      }
    }
  }


  def getUserTeams: Route = authenticate { user =>
    complete { TeamDao.teamsForUser(user).transact(xa).unsafeToFuture }
  }
  def updateUserByEncodedAuthId(authIdEncoded: String): Route = authenticateSuperUser { root =>
    entity(as[User]) { updatedUser =>
      onSuccess(UserDao.updateUser(updatedUser, authIdEncoded).transact(xa).unsafeToFuture()) {
        completeSingleOrNotFound
      }
    }
  }

  def getUserRoles: Route = authenticate { user =>
    complete {
      UserGroupRoleDao.listByUser(user).transact(xa).unsafeToFuture()
    }
  }

  def searchUsers: Route = authenticate { user =>
    searchParams { (searchParams) =>
      complete {
        UserDao.searchUsers(user, searchParams).transact(xa).unsafeToFuture
      }
    }
  }
}
