package com.azavea.rf.common.cache


import com.azavea.rf.common.{Config, RfStackTrace, RollbarNotifier}
import java.util.concurrent.Executors
import net.spy.memcached._

import scala.concurrent._
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import cats.data._
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import com.github.blemale.scaffeine.{Cache, Scaffeine}

import scala.util.{Failure, Success}

object CacheClientThreadPool extends RollbarNotifier {
  implicit lazy val ec: ExecutionContext =
    ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(
        Config.memcached.threads,
        new ThreadFactoryBuilder().setNameFormat("cache-client-%d").build()
      )
    )

  implicit val system = ActorSystem("rollbar-notifier")
  implicit val materializer = ActorMaterializer()
}

class CacheClient(client: => MemcachedClient) extends LazyLogging with RollbarNotifier {

  import CacheClientThreadPool._
  implicit val system = ActorSystem("rollbar-notifier")
  implicit val materializer = ActorMaterializer()

  val cacheEnabled = Config.memcached.enabled
  val localCacheEnabled = true
  val localCache: Cache[String, Option[Any]] =
    Scaffeine()
      .expireAfterWrite(30.seconds)
      .build[String, Option[Any]]()

  def delete(key: String): Unit =
    if(cacheEnabled) {
      client.delete(key)
    }

  def setValue[T](key: String, value: T, ttlSeconds: Int = 0): Unit = {
    logger.debug(s"Setting Key: ${key} with TTL ${ttlSeconds}")
    val f = Future {
      client.set(key, ttlSeconds, value)
    }

    f.onFailure{
      case e => {
        logger.error(s"Error ${e.getMessage}")
        sendError(e)
      }
    }
  }

  // Suppress asInstanceOf warning because we can't pattern match on the returned type since it's
  // eliminated by type erasure
  @SuppressWarnings(Array("AsInstanceOf"))
  def localGetOrElse[CachedType](
    cacheKey: String,
    expensiveOperation: => Future[Option[CachedType]],
    doCache: Boolean = true)(
    fallbackFunction: (String, => Future[Option[CachedType]], Boolean) => Future[Option[CachedType]]
    ): Future[Option[CachedType]] = {

    def fallback: Future[Option[CachedType]] = {
      // Signal to other cache reads that the operation in already in progress
      localCache.put(cacheKey, Some("AWAIT"))
      // Use the fallback function to retrieve the value and cache it
      val fallbackFuture: Future[Option[CachedType]] = fallbackFunction(cacheKey, expensiveOperation, doCache)
      fallbackFuture.onComplete {
        case Success(cachedValueO) => {
          localCache.put(cacheKey, cachedValueO)
        }
        case Failure(e) => {
          sendError(RfStackTrace(e))
          logger.error(s"Cache set error at local cache: ${RfStackTrace(e)}")
        }
      }
      fallbackFuture
    }

    if (cacheEnabled && doCache) {
      localCache.getIfPresent(cacheKey) match {
        // The requested key is not present in the local cache, so do the else function
        case None => {
          // Load the local cache with the result of the else function
          fallback
        }
        // The requested key is in the local cache
        case Some(cachedValueO) => {
          cachedValueO match {
            // The requested key is already being computed, try again
            case Some("AWAIT") => {
              Thread.sleep(25)
              localGetOrElse(cacheKey, expensiveOperation, doCache)(fallbackFunction)
            }
            case Some(cachedValue) => {
              logger.debug(s"Local Cache Hit: ${cacheKey}")
              Future.successful(Some(cachedValue.asInstanceOf[CachedType]))
            }
            case None => {
              fallback
            }
          }
        }
      }
    } else {
      expensiveOperation
    }
  }

  // Suppress asInstanceOf warning because we can't pattern match on the returned type since it's
  // eliminated by type erasure
  @SuppressWarnings(Array("AsInstanceOf"))
  def getOrElseUpdateMemcached[CachedType](
    cacheKey: String,
    expensiveOperation: => Future[Option[CachedType]],
    doCache: Boolean = true
    ): Future[Option[CachedType]] = {
    if (cacheEnabled && doCache) {
      val futureCached = Future { client.asyncGet(cacheKey).get() }
      futureCached.flatMap(
        {
          case null => {
              logger.debug(s"Cache Miss: ${cacheKey}")
              val futureCached: Future[Option[CachedType]] = expensiveOperation
              futureCached.onComplete {
                case Success(cachedValue) => {
                  cachedValue match {
                    case Some(v) => setValue(cacheKey, cachedValue)
                    case None => setValue(cacheKey, cachedValue, ttlSeconds = 300)
                  }
                }
                case Failure(e) => {
                  sendError(RfStackTrace(e))
                  logger.error(s"Cache Set Error: ${RfStackTrace(e)}")
                }
              }
              futureCached
            }
          case o => {
            logger.debug(s"Cache Hit: ${cacheKey}")
            Future.successful(o.asInstanceOf[Option[CachedType]])
          }
        }
      )
    } else {
      expensiveOperation
    }
  }

  def getOrElseUpdate[CachedType](
    cacheKey: String,
    expensiveOperation: => Future[Option[CachedType]],
    doCache: Boolean = true
    ): Future[Option[CachedType]] = {
    if (cacheEnabled && doCache) {
      if (localCacheEnabled) {
        localGetOrElse[CachedType](cacheKey, expensiveOperation, doCache) {
          getOrElseUpdateMemcached[CachedType]
        }
      } else {
        getOrElseUpdateMemcached[CachedType](cacheKey, expensiveOperation, doCache)
      }
    } else {
      expensiveOperation
    }
  }

  def caching[T](
    cacheKey: String,
    doCache: Boolean = true)(
    mappingFunction: => Future[Option[T]]
    ): Future[Option[T]] = {
    getOrElseUpdate[T](cacheKey, mappingFunction, doCache)
  }

  def cachingOptionT[T](
    cacheKey: String,
    doCache: Boolean = true)(
    mappingFunction: => OptionT[Future, T]
    ): OptionT[Future, T] = {
    val futureOption = getOrElseUpdate[T](cacheKey, mappingFunction.value, doCache)
    OptionT(futureOption)
  }

}
