package chatroom

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCodes}
import akka.http.scaladsl.server.{Directives, ExceptionHandler, RejectionHandler}
import akka.pattern.ask
import chatroom.chat.ChatRoomsAndConnections
import chatroom.database.FutureHandler.Blocking
import chatroom.database._
import chatroom.services._
import org.mongodb.scala.bson.ObjectId
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.io.StdIn

trait CustomJsonProtocol extends DefaultJsonProtocol {
  case class CredentialsRequest(username: String, password: String)
  implicit val credentialsRequestFormat: RootJsonFormat[CredentialsRequest] =
    jsonFormat2(CredentialsRequest)

  case class ChatRequest(name: String, users: List[String])
  implicit val chatRequestFormat: RootJsonFormat[ChatRequest] =
    jsonFormat2(ChatRequest)

  case class UsersToChatRequest(chatId: String, users: List[String])
  implicit val UsersToChatRequestFormat: RootJsonFormat[UsersToChatRequest] =
    jsonFormat2(UsersToChatRequest)

  case class FutureHandlingConfiguration(users: Boolean, chats: Boolean, messages: Boolean)
  implicit val futureHandlingConfigurationFormat: RootJsonFormat[FutureHandlingConfiguration] =
    jsonFormat3(FutureHandlingConfiguration)

  case class MessageResponse(chatId: String, chatName: String, date: Long, author: String, message: String)
  implicit val messageResponseFormat: RootJsonFormat[MessageResponse] =
    jsonFormat5(MessageResponse)

  case class ChatNameResponse(chatName: String)
  implicit val chatNameResponseFormat: RootJsonFormat[ChatNameResponse] =
    jsonFormat1(ChatNameResponse)

  case class TokenResponse(token: String, expiresIn: Int)
  implicit val tokenResponseFormat: RootJsonFormat[TokenResponse] =
    jsonFormat2(TokenResponse)

  case class UsernameAvailableResponse(available: Boolean)
  implicit val usernameAvailableResponseFormat: RootJsonFormat[UsernameAvailableResponse] =
    jsonFormat1(UsernameAvailableResponse)
}
object Server extends App with CustomJsonProtocol with SprayJsonSupport {

  import Directives._
  import ImplicitsService._
  import chatroom.database.ChatsActor._
  import chatroom.database.MessagesActor._
  import chatroom.database.UsersActor._
  import scala.concurrent.ExecutionContext.Implicits.global
  import ch.megard.akka.http.cors.scaladsl.CorsDirectives._

  val config = actorSystem.settings.config
  val interface = config.getString("app.interface")
  val port = config.getInt("app.port")

  val rejectionHandler = corsRejectionHandler.withFallback(RejectionHandler.default)
  val exceptionHandler = ExceptionHandler {
    case e: NoSuchElementException => complete(StatusCodes.NotFound -> e.getMessage)
  }
  val handleErrors = handleRejections(rejectionHandler) & handleExceptions(exceptionHandler)

  val signUp =
    (path("user" / "signup") & post) {
      entity(as[CredentialsRequest]) {
        case CredentialsRequest(username, password) =>
          val tokenOptionFuture = AuthorizationService.signUp(username, password, DatabaseService.usersActor)
          onSuccess(tokenOptionFuture) {
            case Some(token) =>
              complete(TokenResponse(token, AuthorizationService.expirationPeriodInMinutes))
            case _ => complete(StatusCodes.Conflict)
          }
        case _ => complete(StatusCodes.BadRequest)
      }
    }

  val login =
    (path("user" / "login") & post) {
      entity(as[CredentialsRequest]) {
        case CredentialsRequest(username, password) =>
          val tokenOptionFuture = AuthorizationService.login(username, password, DatabaseService.usersActor)
          onSuccess(tokenOptionFuture) {
            case Some(token) =>
              complete(TokenResponse(token, AuthorizationService.expirationPeriodInMinutes))
            case _ => complete(StatusCodes.Unauthorized)
          }
        case _ => complete(StatusCodes.Unauthorized)
      }
    }

  val addNewChat =
    (path("chat") & post) {
      AuthorizationService.authenticate() { (username, _, _) =>
        entity(as[ChatRequest]) {
          case ChatRequest(name, users) =>
            val usernameListFuture = (DatabaseService.usersActor ? ExtractActualUsernames(users))
              .mapTo[List[User]]
              .map(_.map(_.username))
            onSuccess(usernameListFuture) { actualUsernames =>
              val chat = Chat(name, username::actualUsernames)
              val insertedFuture = (DatabaseService.chatsActor ? InsertChat(chat)).mapTo[Boolean]
              onSuccess(insertedFuture) {
                case true =>
                  ChatRoomsAndConnections.insertChatRoom(chat)
                  complete(StatusCodes.Created)
                case _ => complete(StatusCodes.InternalServerError)
              }
            }
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  val addUsersToChat =
    (path("chat" / "addUsers") & put) {
      AuthorizationService.authenticate() { (username, _, _) =>
        entity(as[UsersToChatRequest]) {
          case UsersToChatRequest(chatId, users) =>
            val usernameListFuture = (DatabaseService.usersActor ? ExtractActualUsernames(users))
              .mapTo[List[User]]
              .map(_.map(_.username))
            onSuccess(usernameListFuture) { actualUsernames =>
              val updatedFuture = (DatabaseService.chatsActor ? AddUsersToChat(username, new ObjectId(chatId), actualUsernames: List[String])).mapTo[Boolean]
              onSuccess(updatedFuture) {
                case true =>
                  ChatRoomsAndConnections.addUsersToChatRoom(chatId, users)
                  complete(StatusCodes.OK)
                case _ => complete(StatusCodes.Unauthorized)
              }
            }
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  val getUsernamesByPrefix =
    (pathPrefix("user" / "getUsernamesByPrefix") & get) {
      AuthorizationService.authenticate() { (_, _, _) =>
        (path(Segment) | parameter('usernamePrefix)) { usernamePrefix =>
          val usersFuture = (DatabaseService.usersActor ? GetUsersByPrefix(usernamePrefix))
            .mapTo[List[User]]
            .map(_.map(_.username))
          complete(usersFuture)
        }
      }
    }

  val usernameAvailable =
    (pathPrefix("user" / "usernameAvailable") & get) {
      (path(Segment) | parameter('username)) { username =>
        val userFuture = (DatabaseService.usersActor ? GetUserByUsername(username))
          .mapTo[Option[User]]
        onSuccess(userFuture) {
          case None =>
            complete(UsernameAvailableResponse(true))
          case Some(_) =>
            complete(UsernameAvailableResponse(false))
        }
      }
    }

  val getAllMessages =
    (path("message") & get) {
      AuthorizationService.authenticate() { (username, _, _) =>
        val messagesFuture = (DatabaseService.messagesActor ? GetAllMessages(username))
          .mapTo[List[Message]]
          .map(_.map(message => {
            MessageResponse(
              message.chatId.toHexString,
              ChatRoomsAndConnections.GetChatNameById(message.chatId.toHexString),
              message.date,
              message.author,
              message.message)
          }))
        complete(messagesFuture)
      }
    }

  val getChatNameById =
    (pathPrefix("chat" / "getChatNameById") & get) {
      AuthorizationService.authenticate() { (username, _, _) =>
        (path(Segment) | parameter('id)) { chatId =>
          val name = ChatRoomsAndConnections.GetChatNameByIdIfUsernameIsInside(chatId, username)
          if (name != null)
            complete(ChatNameResponse(name))
          else
            complete(StatusCodes.Unauthorized)
        }
      }
    }

  val setFutureHandlingConfiguration =
    (path("setFutureHandlingConfiguration") & post) {
      AuthorizationService.authenticate() { (_, scope, _) =>
        if (!scope.equals("admin"))
          complete(StatusCodes.Unauthorized)
        entity(as[FutureHandlingConfiguration]) {
          case FutureHandlingConfiguration(users, chats, messages) =>
            DatabaseService.usersActor ! Blocking(users)
            DatabaseService.chatsActor ! Blocking(chats)
            DatabaseService.messagesActor ! Blocking(messages)
            complete(StatusCodes.OK)
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  val createWSConnection =
    (path("createWSConnection") & get) {
      AuthorizationService.authenticate("Sec-WebSocket-Protocol") { (username, _, token) =>
        val handler = ChatRoomsAndConnections.createOrGetConnection(username).websocketFlow()
        handleWebSocketMessagesForProtocol(handler, token)
      }
    }

  ChatRoomsAndConnections.GetChatRoomsFromDB(DatabaseService.chatsActor)
  DatabaseService.messagesActor ! Blocking(false)

  val route =
    handleErrors {
      cors() {
        signUp ~
        login ~
        addNewChat ~
        addUsersToChat ~
        getUsernamesByPrefix ~
        usernameAvailable ~
        getAllMessages ~
        getChatNameById ~
        setFutureHandlingConfiguration ~
        createWSConnection
      }
    }
  val bindingFuture = Http().bindAndHandle(route, interface, port)
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => sys.exit())
}
