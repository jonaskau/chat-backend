package chatroom

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
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

  case class GetMessagesRequest(chatId: String, amount: Int, olderThanDate: Long)
  implicit  val GetMessageRequestFormat: RootJsonFormat[GetMessagesRequest] =
    jsonFormat3(GetMessagesRequest)

  case class ChatMessagesResponse(date: Long, author: String, message: String)
  implicit  val ChatMessagesResponseFormat: RootJsonFormat[ChatMessagesResponse] =
    jsonFormat3(ChatMessagesResponse)

  case class FutureHandlingConfiguration(users: Boolean, chats: Boolean, messages: Boolean)
  implicit val futureHandlingConfigurationFormat: RootJsonFormat[FutureHandlingConfiguration] =
    jsonFormat3(FutureHandlingConfiguration)

  /*case class ChatsResponse(id: String, name: String, users: List[String])
  implicit val chatsResponseFormat: RootJsonFormat[ChatsResponse] =
    jsonFormat3(ChatsResponse)

  case class ChatNameResponse(chatName: String)
  implicit val chatNameResponseFormat: RootJsonFormat[ChatNameResponse] =
    jsonFormat1(ChatNameResponse)*/

  case class TokenResponse(token: String, username: String, expiresIn: Int)
  implicit val tokenResponseFormat: RootJsonFormat[TokenResponse] =
    jsonFormat3(TokenResponse)

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
    (path("users" / "signup") & post) {
      entity(as[CredentialsRequest]) {
        case CredentialsRequest(username, password) =>
          val tokenOptionFuture = AuthorizationService.signUp(username, password, DatabaseService.usersActor)
          onSuccess(tokenOptionFuture) {
            case Some(token) =>
              complete(TokenResponse(token, username, AuthorizationService.expirationPeriodInMinutes))
            case _ => complete(StatusCodes.Conflict)
          }
        case _ => complete(StatusCodes.BadRequest)
      }
    }

  val login =
    (path("users" / "login") & post) {
      entity(as[CredentialsRequest]) {
        case CredentialsRequest(username, password) =>
          val tokenOptionFuture = AuthorizationService.login(username, password, DatabaseService.usersActor)
          onSuccess(tokenOptionFuture) {
            case Some(token) =>
              complete(TokenResponse(token, username, AuthorizationService.expirationPeriodInMinutes))
            case _ => complete(StatusCodes.Unauthorized)
          }
        case _ => complete(StatusCodes.Unauthorized)
      }
    }

  val addNewChat =
    (path("chats") & post) {
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
                  complete(StatusCodes.NoContent)
                case _ => complete(StatusCodes.InternalServerError)
              }
            }
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  val addUsersToChat =
    (path("chats" / "addUsers") & put) {
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
                  val newUsers = ChatRoomsAndConnections.addUsersToChatRoom(chatId, users)
                  complete(newUsers)
                case _ => complete(StatusCodes.Unauthorized)
              }
            }
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  val getUsernamesByPrefix =
    (pathPrefix("users" / "getUsernamesByPrefix") & get) {
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
    (pathPrefix("users" / "usernameAvailable") & get) {
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

  val getMessages =
    (path("messages" / "getBatch") & post) {
      AuthorizationService.authenticate() { (username, _, _) =>
        entity(as[GetMessagesRequest]) {
          case GetMessagesRequest(chatId, amount, olderThanDate) =>
            if (!ChatRoomsAndConnections.chatRoomContainsUsername(chatId, username)) {
              complete(StatusCodes.Unauthorized)
            }
            val messageListFuture = (DatabaseService.messagesActor ? GetMessages(new ObjectId(chatId), amount, olderThanDate))
              .mapTo[List[Message]]
              .map(_.map(message => {
                ChatMessagesResponse(message.date, message.author, message.message)
              }))
            complete(messageListFuture)
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  /*val getChats =
  (path("chats") & get) {
    AuthorizationService.authenticate() { (username, _, _) =>
      val chatsFuture = (DatabaseService.chatsActor ? GetChatsForUsername(username))
        .mapTo[List[Chat]]
        .map(_.map(chat => {
          ChatsResponse(chat._id.toHexString, chat.name, chat.users)
        }))
      complete(chatsFuture)
    }
  }*/

  /*val getChatNameById =
    (pathPrefix("chats" / "getChatNameById") & get) {
      AuthorizationService.authenticate() { (username, _, _) =>
        (path(Segment) | parameter('id)) { chatId =>
          val name = ChatRoomsAndConnections.GetChatNameByIdIfUsernameIsInside(chatId, username)
          if (name != null)
            complete(ChatNameResponse(name))
          else
            complete(StatusCodes.Unauthorized)
        }
      }
    }*/

  /*val getChatById =
    (pathPrefix("chats") & get) {
      AuthorizationService.authenticate() { (username, _, _) =>
        (path(Segment) | parameter('id)) { chatId =>
          val chatTuple = ChatRoomsAndConnections.GetChatByIdIfUsernameIsInside(chatId, username)
          if (chatTuple != null)
            complete(ChatsResponse(chatId, chatTuple._1, chatTuple._2))
          else
            complete(StatusCodes.Unauthorized)
        }
      }
    }*/

  /*val getOnlineUsers =
  (pathPrefix("chats" / "getOnlineUsers") & get) {
    AuthorizationService.authenticate() { (username, _, _) =>
      (path(Segment) | parameter('chatId)) { chatId =>
        if (!ChatRoomsAndConnections.chatRoomContainsUsername(chatId, username)) {
          complete(StatusCodes.Unauthorized)
        }
        val userOnlineListFuture = ChatRoomsAndConnections.GetOnlineUsers(chatId)
        complete(userOnlineListFuture)
      }
    }
  }*/

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
        getMessages ~
        /*getOnlineUsers ~
        getChats ~
        getChatNameById ~
        getChatById ~*/
        setFutureHandlingConfiguration ~
        createWSConnection
      }
    }
  val bindingFuture = Http().bindAndHandle(route, interface, port)
  println(s"Server online at http://$interface:$port/\nPress RETURN to stop...")
  StdIn.readLine()
  /*bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => sys.exit())*/
}
