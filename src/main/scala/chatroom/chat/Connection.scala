package chatroom.chat

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.{FlowShape, OverflowStrategy}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Sink, Source}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

object ConnectionJsonProtocol extends DefaultJsonProtocol {
  import chatroom.chat.ConnectionActor.SendIncomingMessage
  implicit val SendIncomingMessageFormat: RootJsonFormat[SendIncomingMessage] =
    jsonFormat3(SendIncomingMessage)

  import chatroom.chat.ChatRoomActor.OutgoingMessage
  implicit val OutgoingMessageFormat: RootJsonFormat[OutgoingMessage] =
    jsonFormat3(OutgoingMessage)
}

object Connection {
  def apply(username: String)(implicit actorSystem: ActorSystem) =
    new Connection(username, actorSystem)
}
class Connection(val username: String, actorSystem: ActorSystem) {
  import ChatRoomActor._
  import ConnectionActor._
  import ConnectionJsonProtocol._
  import spray.json._

  private val connectionActor = actorSystem.actorOf(Props[ConnectionActor], username)

  def websocketFlow(): Flow[Message, Message, _] = {
    Flow.fromGraph(GraphDSL.create(Source.actorRef[OutgoingMessage](bufferSize = 5, OverflowStrategy.fail)) {
      implicit builder => chatSource =>
        import GraphDSL.Implicits._

        val fromWebsocket = builder.add(
          Flow[Message].collect {
            case TextMessage.Strict(txt) => txt.parseJson.convertTo[SendIncomingMessage]
          })

        val backToWebsocket = builder.add(
          Flow[OutgoingMessage].map {
            om: OutgoingMessage => TextMessage(om.toJson.compactPrint)
          }
        )

        val actorAsSource = builder.materializedValue.map(actor => SendUserOnline(username, actor))

        val chatActorSink = Sink.actorRef[ConnectionEvent](connectionActor, SendUserOffline(username))

        val merge = builder.add(Merge[ConnectionEvent](2))

        fromWebsocket ~> merge.in(0)
        actorAsSource ~> merge.in(1)
        merge         ~> chatActorSink
        chatSource    ~> backToWebsocket

        FlowShape(fromWebsocket.in, backToWebsocket.out)
    })
  }

  def addChatRoomActor(chatId: String, chatRoomActor: ActorRef): Unit = {
    connectionActor ! AddChatRoomActor(chatId, chatRoomActor)
  }

  def addChatRoomActorAndSendUserOnline(chatId: String, chatRoomActor: ActorRef): Unit = {
    connectionActor ! AddChatRoomActorAndSendUserOnline(username, chatId, chatRoomActor)
  }
}
