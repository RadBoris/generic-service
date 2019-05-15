import akka.actor.{Actor, ActorSystem, ActorRef, Props}
import akka.event.Logging
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashMap

import scala.util.Random

class GenericCell(var prev: BigInt, var next: BigInt)
class GenericMap extends scala.collection.mutable.HashMap[BigInt, GenericCell]

/**
 * GenericService is an example app service for the actor-based KVStore/KVClient.
 * This one stores Generic Cell objects in the KVStore.  Each app server allocates new
 * GenericCells (allocCell), writes them, and reads them randomly with consistency
 * checking (touchCell).  The allocCell and touchCell commands use direct reads
 * and writes to bypass the client cache.  Keeps a running set of Stats for each burst.
 *
 * @param myNodeID sequence number of this actor/server in the app tier
 * @param numNodes total number of servers in the app tier
 * @param storeServers the ActorRefs of the KVStore servers
 * @param burstSize number of commands per burst
 */

class GenericServer (val myNodeID: Int, val numNodes: Int, storeServers: Seq[ActorRef], burstSize: Int) extends Actor {
  val generator = new scala.util.Random
  val listNum = new scala.util.Random(5)
  val cellstore = new KVClient(storeServers)
  val dirtycells = new AnyMap
  val localWeight: Int = 70
  val log = Logging(context.system, this)

  var stats = new Stats
  var allocated: Int = 0
  var endpoints: Option[Seq[ActorRef]] = None
  var my_list = new ListBuffer[ActorRef]()
  var my_list2 = new ListBuffer[ActorRef]()

  def receive() = {
      case Prime() =>
        allocCell
      case Command() =>
        statistics(sender)
        command
      case View(e) =>
        endpoints = Some(e)
        val check = generator.nextInt(100)

        if (check > 0 && check < 30) {
            val f = Random.shuffle(e.toList).head
            if (!my_list.contains(f)) {
                my_list+=f
            }
          my_list.foreach { l =>
            l ! "added to group 1"
            println("sent message to list")
          }
        }

        if (check > 31  && check < 67) {
          val f = Random.shuffle(e.toList).head

           if (!my_list2.contains(f)) {
              my_list2+=f
           }
            my_list2.foreach { l =>
                l ! "added to group 2"
                println("sent message to list 2")
            }
        }

        if (check >= 67) {

          if (!my_list.isEmpty) {

              val f = Random.shuffle(my_list).head

              my_list -=f

              println("removed from list" + f)
            }
        }

      case message =>
        println("received message")
        println("message from " + sender)
        println(message)
  }

  private def command() = {
    val sample = generator.nextInt(100)
    if (sample > 0 && sample < 20) {
      allocCell
    } else {
      touchCell
    }
  }

  private def statistics(master: ActorRef) = {
    stats.messages += 1
    if (stats.messages >= burstSize) {
      master ! BurstAck(myNodeID, stats)
      stats = new Stats
    }
  }


  private def messageAck(master: ActorRef) = {
      master ! toMaster("I have been added")
  }

  private def allocCell() = {
    val key = chooseEmptyCell
    var cell = directRead(key)
    assert(cell.isEmpty)
    val r = new GenericCell(0, 1)
    stats.allocated += 1
    directWrite(key, r)
  }

  private def chooseEmptyCell(): BigInt =
  {
    allocated = allocated + 1
    cellstore.hashForKey(myNodeID, allocated)
  }

  private def touchCell() = {
    stats.touches += 1
    val key = chooseActiveCell
    val cell = directRead(key)
    if (cell.isEmpty) {
      stats.misses += 1
    } else {
      val r = cell.get
      if (r.next != r.prev + 1) {
        stats.errors += 1
        r.prev = 0
        r.next = 1
      } else {
        r.next += 1
        r.prev += 1
      }
      directWrite(key, r)
    }
  }

  private def chooseActiveCell(): BigInt = {
    val chosenNodeID =
      if (generator.nextInt(100) <= localWeight)
        myNodeID
      else
        generator.nextInt(numNodes - 1)

    val cellSeq = generator.nextInt(allocated)
    cellstore.hashForKey(chosenNodeID, cellSeq)
  }

  private def rwcheck(key: BigInt, value: GenericCell) = {
    directWrite(key, value)
    val returned = read(key)
    if (returned.isEmpty)
      println("rwcheck failed: empty read")
    else if (returned.get.next != value.next)
      println("rwcheck failed: next match")
    else if (returned.get.prev != value.prev)
      println("rwcheck failed: prev match")
    else
      println("rwcheck succeeded")
  }

  private def read(key: BigInt): Option[GenericCell] = {
    val result = cellstore.read(key)
    if (result.isEmpty) None else
      Some(result.get.asInstanceOf[GenericCell])
  }

  private def write(key: BigInt, value: GenericCell, dirtyset: AnyMap): Option[GenericCell] = {
    val coercedMap: AnyMap = dirtyset.asInstanceOf[AnyMap]
    val result = cellstore.write(key, value, coercedMap)
    if (result.isEmpty) None else
      Some(result.get.asInstanceOf[GenericCell])
  }

  private def directRead(key: BigInt): Option[GenericCell] = {
    val result = cellstore.directRead(key)
    if (result.isEmpty) None else
      Some(result.get.asInstanceOf[GenericCell])
  }

  private def directWrite(key: BigInt, value: GenericCell): Option[GenericCell] = {
    val result = cellstore.directWrite(key, value)
    if (result.isEmpty) None else
      Some(result.get.asInstanceOf[GenericCell])
  }

  private def push(dirtyset: AnyMap) = {
    cellstore.push(dirtyset)
  }
}

object GenericServer {
  def props(myNodeID: Int, numNodes: Int, storeServers: Seq[ActorRef], burstSize: Int): Props = {
    Props(classOf[GenericServer], myNodeID, numNodes, storeServers, burstSize)
  }
}
