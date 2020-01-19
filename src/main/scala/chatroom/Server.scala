package chatroom

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives
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

  case class MessageResponse(chatId: String, date: Long, author: String, message: String)
  implicit val messageResponseFormat: RootJsonFormat[MessageResponse] =
    jsonFormat4(MessageResponse)

  case class UsersToChatRequest(chatId: String, users: List[String])
  implicit val UsersToChatRequestFormat: RootJsonFormat[UsersToChatRequest] =
    jsonFormat2(UsersToChatRequest)

  case class FutureHandlingConfiguration(users: Boolean, chats: Boolean, messages: Boolean)
  implicit val futureHandlingConfigurationFormat: RootJsonFormat[FutureHandlingConfiguration] =
    jsonFormat3(FutureHandlingConfiguration)
}
object Server extends App with CustomJsonProtocol with SprayJsonSupport {

  import Directives._
  import ImplicitsService._
  import chatroom.database.ChatsActor._
  import chatroom.database.MessagesActor._
  import chatroom.database.UsersActor._

  import scala.concurrent.ExecutionContext.Implicits.global

  val config = actorSystem.settings.config
  val interface = config.getString("app.interface")
  val port = config.getInt("app.port")

  val openRoute =
    post {
      path("login") {
        entity(as[CredentialsRequest]) {
          case CredentialsRequest(username, password) =>
            val tokenOptionFuture = AuthorizationService.login(username, password, DatabaseService.usersActor)
            onSuccess(tokenOptionFuture) {
              case Some(token) =>
                respondWithHeader(RawHeader("Access-Token", token)) {
                  complete(StatusCodes.OK)
                }
              case _ => complete(StatusCodes.Unauthorized)
            }
          case _ => complete(StatusCodes.Unauthorized)
        }
      } ~
      path("signup") {
        entity(as[CredentialsRequest]) {
          case CredentialsRequest(username, password) =>
            val tokenOptionFuture = AuthorizationService.signUp(username, password, DatabaseService.usersActor)
            onSuccess(tokenOptionFuture) {
              case Some(token) =>
                respondWithHeader(RawHeader("Access-Token", token)) {
                  complete(StatusCodes.OK)
                }
              case _ => complete(StatusCodes.Conflict)
            }
          case _ => complete(StatusCodes.BadRequest)
        }
      }
    }

  val addNewChat =
    (path("addNewChat") & post) {
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
    (path("addUsersToChat") & post) {
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

  val getAllMessages =
    (path("getAllMessages") & get) {
      AuthorizationService.authenticate() { (username, _, _) =>
        val messagesFuture = (DatabaseService.messagesActor ? GetAllMessages(username))
          .mapTo[List[Message]]
          .map(_.map(message => {
            MessageResponse(message.chatId.toHexString, message.date, message.author, message.message)
          }))
        complete(messagesFuture)
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
        val handler = ChatRoomsAndConnections.createConnection(username).websocketFlow()
        handleWebSocketMessagesForProtocol(handler, token)
      }
    }

  ChatRoomsAndConnections.GetChatRoomsFromDB(DatabaseService.chatsActor)
  DatabaseService.messagesActor ! Blocking(false)
  val route =
    openRoute ~
    addNewChat ~
    addUsersToChat ~
    getAllMessages ~
    setFutureHandlingConfiguration ~
    createWSConnection
  val bindingFuture = Http().bindAndHandle(route, interface, port)
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
}
