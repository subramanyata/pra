package edu.cmu.ml.rtw.pra.graphs

import edu.cmu.ml.rtw.pra.experiments.Outputter
import edu.cmu.ml.rtw.users.matt.util.FileUtil
import edu.cmu.ml.rtw.users.matt.util.JsonHelper
import edu.cmu.ml.rtw.users.matt.util.SpecFileReader

import scala.collection.concurrent
import scala.collection.mutable
import scala.collection.JavaConverters._

import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

import org.json4s._

object RemoteGraph {
  val DEFAULT_PORT = 9876
}

class RemoteGraph(val hostname: String, val port: Int, val numConnections: Int) extends Graph {
  println("Starting remote graph")
  lazy val sockets = {
    println("Opening $numConnections connections to graph server")
    val queue = new LinkedBlockingQueue[(Socket, ObjectOutputStream, ObjectInputStream)]
    (1 to numConnections).map(i => {
      val socket = new Socket(hostname, port)
      val out = new ObjectOutputStream(socket.getOutputStream)
      val in = new ObjectInputStream(socket.getInputStream)
      queue.put((socket, out, in))
    })
    queue
  }

  def close() = sockets.asScala.map(_._1.close())

  val nodeNames = new concurrent.TrieMap[Int, String]
  val nodeIds = new concurrent.TrieMap[String, Int]
  val nodes = new concurrent.TrieMap[Int, Node]
  val nodesNotPresent = new concurrent.TrieMap[String, Boolean]

  val edgeNames = new concurrent.TrieMap[Int, String]
  val edgeIds = new concurrent.TrieMap[String, Int]
  val edgesNotPresent = new concurrent.TrieMap[String, Boolean]

  lazy val (numNodes, numEdgeTypes) = {
    println("Getting graph stats from server")
    val result = getFromServer[StatsResponse](GetGraphStats)
    (result.numNodes, result.numEdgeTypes)
  }

  override def entries = throw new RuntimeException("cannot get entries array on remote graph")

  override def getNode(i: Int): Node = {
    nodes.get(i) match {
      case Some(node) => node
      case None => {
        getNodeFromServer(i)._1
      }
    }
  }

  override def getNode(name: String): Node = {
    val id = nodeIds.get(name) match {
      case Some(index) => index
      case None => getNodeFromServer(name)._2
    }
    nodes(id)
  }

  override def getNodeName(i: Int): String = {
    nodeNames.get(i) match {
      case Some(name) => name
      case None => getNodeFromServer(i)._2
    }
  }

  override def hasNode(name: String): Boolean = {
    nodeIds.get(name) match {
      case Some(id) => true
      case None => {
        getNodeFromServer(name)
        !nodesNotPresent.contains(name)
      }
    }
  }

  override def getNodeIndex(name: String): Int = {
    nodeIds.get(name) match {
      case Some(id) => id
      case None => getNodeFromServer(name)._2
    }
  }

  override def getNumNodes(): Int = numNodes

  override def getEdgeName(i: Int): String = {
    edgeNames.get(i) match {
      case Some(name) => name
      case None => getEdgeFromServer(i)._2
    }
  }

  override def hasEdge(name: String): Boolean = {
    edgeIds.get(name) match {
      case Some(id) => true
      case None => {
        getEdgeFromServer(name)
        !edgesNotPresent.contains(name)
      }
    }
  }

  override def getEdgeIndex(name: String): Int = {
    edgeIds.get(name) match {
      case Some(id) => id
      case None => getEdgeFromServer(name)._1
    }
  }

  override def getNumEdgeTypes(): Int = numEdgeTypes

  private def getNodeFromServer(id: Int): (Node, String) = {
    println("Getting node " + id + " from server")
    val result = getFromServer[NodeResponse](GetNodeById(id))
    updateNodeCache(result)
    result match {
      case NodePresent(id, name, node) => (node, name)
      case NodeAbsent(name) => (null, name)
    }
  }

  private def getNodeFromServer(name: String): (Node, Int) = {
    println("Getting node " + name + " from server")
    val result = getFromServer[NodeResponse](GetNodeByName(name))
    updateNodeCache(result)
    result match {
      case NodePresent(id, name, node) => (node, id)
      case NodeAbsent(name) => (null, -1)
    }
  }

  private def getEdgeFromServer(id: Int): (Int, String) = {
    println("Getting edge " + id + " from server")
    val result = getFromServer[EdgeResponse](GetEdgeById(id))
    updateEdgeCache(result)
    result match {
      case EdgePresent(id, name) => (id, name)
      case EdgeAbsent(name) => (id, null)
    }
  }

  private def getEdgeFromServer(name: String): (Int, String) = {
    println("Getting edge " + name + " from server")
    val result = getFromServer[EdgeResponse](GetEdgeByName(name))
    updateEdgeCache(result)
    result match {
      case EdgePresent(id, name) => (id, name)
      case EdgeAbsent(name) => (-1, name)
    }
  }

  def getFromServer[T <: GraphResponse](message: GraphMessage): T = {
    val (socket, out, in) = sockets.take()
    out.writeObject(message)
    val result = in.readObject().asInstanceOf[T]
    sockets.put((socket, out, in))
    result
  }

  // We don't need to worry about checking the return values in either of these two methods,
  // because if another thread updated the cache, it will have put the same value in.  We assume
  // that the graph server we're talking to has an immutable graph.
  private def updateNodeCache(response: NodeResponse) {
    response match {
      case NodePresent(id, name, node) => {
        nodeNames.putIfAbsent(id, name)
        nodeIds.putIfAbsent(name, id)
        nodes.putIfAbsent(id, node)
      }
      case NodeAbsent(name) => {
        nodesNotPresent.putIfAbsent(name, true)
      }
    }
  }

  private def updateEdgeCache(response: EdgeResponse) {
    response match {
      case EdgePresent(id, name) => {
        edgeNames.putIfAbsent(id, name)
        edgeIds.putIfAbsent(name, id)
      }
      case EdgeAbsent(name) => {
        edgesNotPresent.putIfAbsent(name, true)
      }
    }
  }
}

sealed trait GraphMessage
final case class GetNodeById(id: Int) extends GraphMessage
final case class GetNodeByName(name: String) extends GraphMessage
final case class GetEdgeById(id: Int) extends GraphMessage
final case class GetEdgeByName(name: String) extends GraphMessage
final case object GetGraphStats extends GraphMessage

trait GraphResponse
trait NodeResponse extends GraphResponse
case class NodePresent(id: Int, name: String, node: Node) extends NodeResponse
case class NodeAbsent(name: String) extends NodeResponse

trait EdgeResponse extends GraphResponse
case class EdgePresent(id: Int, name: String) extends EdgeResponse
case class EdgeAbsent(name: String) extends EdgeResponse

final case class StatsResponse(numNodes: Int, numEdgeTypes: Int) extends GraphResponse


class RemoteGraphServer(graph: Graph, port: Int) extends Thread {

  val serverSocket = new ServerSocket(port)
  val handlerSockets = new mutable.ListBuffer[Socket]

  override def run() {
    while (true) {
      try {
        val socket = serverSocket.accept()
        handlerSockets += socket
        new Handler(socket).start()
      } catch {
        case e: InterruptedException => {
          println("Closing the server socket")
          serverSocket.close()
          throw e
        }
        case e: java.net.SocketException => {
          println("Socket closed!")
          return
        }
      }
    }
  }

  def quit() {
    println("Closing the socket")
    serverSocket.close()
    println("Closing handler sockets")
    handlerSockets.map(_.close())
  }

  class Handler(socket: Socket) extends Thread {
    println("Got a new client")
    println("isConnected: " + socket.isConnected)
    val out = new ObjectOutputStream(socket.getOutputStream)
    val in = new ObjectInputStream(socket.getInputStream)

    override def run() {
      println("Handling requests")
      while (!socket.isClosed) {
        try {
          println("Waiting for message")
          val message = in.readObject()
          message match {
            case GetNodeById(id) => {
              println("Requested node " + id)
              val node = graph.getNode(id)
              val nodeName = graph.getNodeName(id)
              out.writeObject(NodePresent(id, nodeName, node))
            }
            case GetNodeByName(name) => {
              println("Requested node " + name)
              if (graph.hasNode(name)) {
                val node = graph.getNode(name)
                val id = graph.getNodeIndex(name)
                out.writeObject(NodePresent(id, name, node))
              } else {
                out.writeObject(NodeAbsent(name))
              }
            }
            case GetEdgeById(id) => {
              println("Requested edge " + id)
              val name = graph.getEdgeName(id)
              out.writeObject(EdgePresent(id, name))
            }
            case GetEdgeByName(name) => {
              println("Requested edge " + name)
              if (graph.hasEdge(name)) {
                val id = graph.getEdgeIndex(name)
                out.writeObject(EdgePresent(id, name))
              } else {
                out.writeObject(EdgeAbsent(name))
              }
            }
            case GetGraphStats => {
              println("Requested graph stats")
              out.writeObject(StatsResponse(graph.getNumNodes(), graph.getNumEdgeTypes()))
            }
          }
        } catch {
          case e: InterruptedException => {
            println("Closing the handler socket")
            socket.close()
          }
          case e: java.io.EOFException => {
            println("Handler socket closed remotely")
            socket.close()
          }
        }
      }
    }
  }
}

object RunRemoteGraphServer {
  var fileUtil = new FileUtil

  def parseArgs(args: List[String]): Map[String, String] = {
    def nextOption(map: Map[String, String], list: List[String]): Map[String, String] = {
      list match {
        case Nil => map
        case "--graph" :: value :: tail => nextOption(map ++ Map("graph" -> value), tail)
        case "--port" :: value :: tail => nextOption(map ++ Map("port" -> value), tail)
        case "--spec" :: value :: tail => nextOption(map ++ Map("spec" -> value), tail)
        case option :: tail => {
          throw new RuntimeException("unknown option: " + option)
        }
      }
    }
    nextOption(Map(), args)
  }

  def readSpecFile(specFile: String): (String, Int) = {
    implicit val formats = DefaultFormats
    val params = new SpecFileReader("/dev/null", fileUtil).readSpecFile(specFile)
    val paramKeys = Seq("type", "port", "graph")
    JsonHelper.ensureNoExtras(params, "main", paramKeys)
    val port = JsonHelper.extractWithDefault(params, "port", RemoteGraph.DEFAULT_PORT)
    val graph = (params \ "graph").extract[String]
    (graph, port)
  }

  def main(args: Array[String]) {
    val options = parseArgs(args.toList)
    val (graphFile, port) = options.get("spec") match {
      case Some(specFile) => readSpecFile(specFile)
      case None => {
        (options("graph"), options("port").toInt)
      }
    }

    val praBase = "/dev/null"
    val outputter = new Outputter(JNothing, praBase, "running graph")
    val graph = Graph.create(JString(graphFile), praBase, outputter, fileUtil).get
    val server = new RemoteGraphServer(graph, port)
    println(s"Starting graph server on port $port")
    server.start()
    try {
      println("Hit enter to stop the server...")
      val in = scala.io.Source.stdin.bufferedReader.readLine
    } finally {
      println("Quitting")
      server.quit()
    }
  }
}
