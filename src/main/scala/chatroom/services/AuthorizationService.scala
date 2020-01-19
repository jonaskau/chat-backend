package chatroom.services

import java.security.MessageDigest
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server._
import akka.pattern.ask
import akka.util.Timeout
import chatroom.database.User
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtSprayJson}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object AuthorizationService {

  val salt = "my-salt-text"
  val algorithm: JwtAlgorithm.HS256.type = JwtAlgorithm.HS256
  val secretKey = "chatroomsecret"

  import Directives._
  import chatroom.database.UsersActor._

  implicit val defaultTimeout: Timeout = Timeout(10, TimeUnit.SECONDS)

  def authenticate(headerName: String = "Access-Token"): Directive[(String, String, String)] = {
    optionalHeaderValueByName(headerName).flatMap {
      case Some(token) =>
        if (isTokenValid(token)) {
          if (isTokenExpired(token)) {
            complete(HttpResponse(status = StatusCodes.Unauthorized, entity = "Token expired."))
          } else {
            val (username, scope) = getUserAndScopeOutOfToken(token)
            tprovide(username, scope, token)
          }
        } else {
          complete(HttpResponse(status = StatusCodes.Unauthorized, entity = "Token is invalid, or has been tampered with."))
        }
      case _ => complete(HttpResponse(status = StatusCodes.Unauthorized, entity = "No token provided!"))
    }
  }

  def signUp(username: String, password: String, usersActor: ActorRef)(implicit executionContext: ExecutionContext): Future[Option[String]] = {
    val hash = generateHash(password)
    val userInsertedFuture = (usersActor ? InsertUser(username, hash)).mapTo[Boolean]
    userInsertedFuture.map[Option[String]] {
      case true => Some(createToken(1, username, "basic"))
      case false => None
    }
  }

  def login(username: String, password: String, usersActor: ActorRef)(implicit executionContext: ExecutionContext): Future[Option[String]] = {
    val userOptionFuture = (usersActor ? GetUserByUsername(username)).mapTo[Option[User]]
    userOptionFuture.map[Option[String]] {
      case Some(user) =>
        if (user.password.equals(generateHash(password)))
          Some(createToken(1, username, user.scope))
        else
          None
      case None =>
        None
    }
  }

  def generateHash(password: String): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.digest((salt + password).getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  def createToken(expirationPeriodInDays: Int, username: String, scope: String): String = {
    val claims = JwtClaim(
      expiration = Some(System.currentTimeMillis() / 1000 + TimeUnit.DAYS.toSeconds(expirationPeriodInDays)),
      issuedAt = Some(System.currentTimeMillis() / 1000),
      issuer = Some(username),
      subject = Some(scope)
    )
    JwtSprayJson.encode(claims, secretKey, algorithm) // JWT string
  }

  def isTokenValid(token: String): Boolean = JwtSprayJson.isValid(token, secretKey, Seq(algorithm))

  def isTokenExpired(token: String): Boolean = JwtSprayJson.decode(token, secretKey, Seq(algorithm)) match {
    case Success(claims) => claims.expiration.getOrElse(0L) < System.currentTimeMillis() / 1000
    case Failure(_) => true
  }

  def getUserAndScopeOutOfToken(token: String): (String, String) = JwtSprayJson.decode(token, secretKey, Seq(algorithm)) match {
    case Success(claims) => (claims.issuer.getOrElse(""), claims.subject.getOrElse("basic"))
    case Failure(_) => ("", "")
  }
}
