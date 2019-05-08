import akka.actor.{Actor, ActorSystem, ActorRef, Props}
import akka.event.Logging
import scala.collection.mutable.ListBuffer

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
  //var my_list1 =  ListBuffer[BigInt]()
  var my_list1 = new ListBuffer[BigInt]()
  // val my_list2 =  List[BigInt]()
  // val my_list3 =  List[BigInt]()
  // val my_list4 =  List[BigInt]()

  def receive() = {
      case Prime() =>
        allocCell(my_list1)
      case Command() =>
        statistics(sender)
        command
      case View(e) =>
        endpoints = Some(e)
  }

  private def command() = {
    val sample = generator.nextInt(100)
    if (sample > 0 && sample < 20) {
      allocCell(my_list1)
    }
    // else if (sample > 20 && sample < 40){
    //   allocCell(my_list2)
    // } else if (sample > 40 && sample < 80){
    //   allocCell(my_list3)
    // }
    else {
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

  private def allocCell(my_list:ListBuffer[BigInt]) = {
    val key = chooseEmptyCell
            // println(key)

    my_list+=key
    println("list")
        // println(my_list)

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
