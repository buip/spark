package spark.scheduler

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import spark._
import spark.scheduler._
import spark.scheduler.cluster._
import scala.collection.mutable.ArrayBuffer

import java.util.Properties

class DummyTaskSetManager(
    initPriority: Int,
    initStageId: Int,
    initNumTasks: Int,
    clusterScheduler: ClusterScheduler,
    taskSet: TaskSet)
  extends TaskSetManager(clusterScheduler,taskSet) {

  parent = null
  weight = 1
  minShare = 2
  runningTasks = 0
  priority = initPriority
  stageId = initStageId
  name = "TaskSet_"+stageId
  override val numTasks = initNumTasks
  tasksFinished = 0

  override def increaseRunningTasks(taskNum: Int) {
    runningTasks += taskNum
    if (parent != null) {
      parent.increaseRunningTasks(taskNum)
    }
  }

  override def decreaseRunningTasks(taskNum: Int) {
    runningTasks -= taskNum
    if (parent != null) {
      parent.decreaseRunningTasks(taskNum)
    }
  }

  override def addSchedulable(schedulable: Schedulable) {
  }

  override def removeSchedulable(schedulable: Schedulable) {
  }

  override def getSchedulableByName(name: String): Schedulable = {
    return null
  }

  override def executorLost(executorId: String, host: String): Unit = {
  }

  override def slaveOffer(execId: String, host: String, avaiableCpus: Double): Option[TaskDescription] = {
    if (tasksFinished + runningTasks < numTasks) {
      increaseRunningTasks(1)
      return Some(new TaskDescription(0, stageId.toString, execId, "task 0:0", null))
    }
    return None
  }

  override def checkSpeculatableTasks(): Boolean = {
    return true
  }

//  override def getSortedLeafSchedulable(): ArrayBuffer[Schedulable] = {
//    var leafSchedulableQueue = new ArrayBuffer[Schedulable]
//    leafSchedulableQueue += this
//    return leafSchedulableQueue
//  }

  def taskFinished() {
    decreaseRunningTasks(1)
    tasksFinished +=1
    if (tasksFinished == numTasks) {
      parent.removeSchedulable(this)
    }
  }

  def abort() {
    decreaseRunningTasks(runningTasks)
    parent.removeSchedulable(this)
  }
}

class DummyTask(stageId: Int) extends Task[Int](stageId)
{
  def run(attemptId: Long): Int = {
    return 0
  }
}

class ClusterSchedulerSuite extends FunSuite with BeforeAndAfter {

  val sc = new SparkContext("local", "ClusterSchedulerSuite")
  val clusterScheduler = new ClusterScheduler(sc)
  var tasks = ArrayBuffer[Task[_]]()
  val task = new DummyTask(0)
  val taskSet = new TaskSet(tasks.toArray,0,0,0,null)
  tasks += task

  def createDummyTaskSetManager(priority: Int, stage: Int, numTasks: Int): DummyTaskSetManager = {
    new DummyTaskSetManager(priority, stage, numTasks, clusterScheduler, taskSet)
  }

  def resourceOffer(rootPool: Pool): Int = {
    val taskSetQueue = rootPool.getSortedTaskSetQueue()
    for (taskSet <- taskSetQueue)
    {
      taskSet.slaveOffer("execId_1", "hostname_1", 1) match {
        case Some(task) =>
          return task.taskSetId.toInt
        case None => {}
      }
    }
    -1
  }

  def checkTaskSetId(rootPool: Pool, expectedTaskSetId: Int) {
    assert(resourceOffer(rootPool) === expectedTaskSetId)
  }

  test("FIFO Scheduler Test") {
    val rootPool = new Pool("", SchedulingMode.FIFO, 0, 0)
    val schedulableBuilder = new FIFOSchedulableBuilder(rootPool)
    schedulableBuilder.buildPools()

    val taskSetManager0 = createDummyTaskSetManager(0, 0, 2)
    val taskSetManager1 = createDummyTaskSetManager(0, 1, 2)
    val taskSetManager2 = createDummyTaskSetManager(0, 2, 2)
    schedulableBuilder.addTaskSetManager(taskSetManager0, null)
    schedulableBuilder.addTaskSetManager(taskSetManager1, null)
    schedulableBuilder.addTaskSetManager(taskSetManager2, null)

    checkTaskSetId(rootPool, 0)
    resourceOffer(rootPool)
    checkTaskSetId(rootPool, 1)
    resourceOffer(rootPool)
    taskSetManager1.abort()
    checkTaskSetId(rootPool, 2)
  }

  test("Fair Scheduler Test") {
    val xmlPath = getClass.getClassLoader.getResource("fairscheduler.xml").getFile()
    System.setProperty("spark.fairscheduler.allocation.file", xmlPath)
    val rootPool = new Pool("", SchedulingMode.FAIR, 0, 0)
    val schedulableBuilder = new FairSchedulableBuilder(rootPool)
    schedulableBuilder.buildPools()

    assert(rootPool.getSchedulableByName("default") != null)
    assert(rootPool.getSchedulableByName("1") != null)
    assert(rootPool.getSchedulableByName("2") != null)
    assert(rootPool.getSchedulableByName("3") != null)
    assert(rootPool.getSchedulableByName("1").minShare === 2)
    assert(rootPool.getSchedulableByName("1").weight === 1)
    assert(rootPool.getSchedulableByName("2").minShare === 3)
    assert(rootPool.getSchedulableByName("2").weight === 1)
    assert(rootPool.getSchedulableByName("3").minShare === 2)
    assert(rootPool.getSchedulableByName("3").weight === 1)

    val properties1 = new Properties()
    properties1.setProperty("spark.scheduler.cluster.fair.pool","1")
    val properties2 = new Properties()
    properties2.setProperty("spark.scheduler.cluster.fair.pool","2")

    val taskSetManager10 = createDummyTaskSetManager(1, 0, 1)
    val taskSetManager11 = createDummyTaskSetManager(1, 1, 1)
    val taskSetManager12 = createDummyTaskSetManager(1, 2, 2)
    schedulableBuilder.addTaskSetManager(taskSetManager10, properties1)
    schedulableBuilder.addTaskSetManager(taskSetManager11, properties1)
    schedulableBuilder.addTaskSetManager(taskSetManager12, properties1)

    val taskSetManager23 = createDummyTaskSetManager(2, 3, 2)
    val taskSetManager24 = createDummyTaskSetManager(2, 4, 2)
    schedulableBuilder.addTaskSetManager(taskSetManager23, properties2)
    schedulableBuilder.addTaskSetManager(taskSetManager24, properties2)

    checkTaskSetId(rootPool, 0)
    checkTaskSetId(rootPool, 3)
    checkTaskSetId(rootPool, 3)
    checkTaskSetId(rootPool, 1)
    checkTaskSetId(rootPool, 4)
    checkTaskSetId(rootPool, 2)
    checkTaskSetId(rootPool, 2)
    checkTaskSetId(rootPool, 4)

    taskSetManager12.taskFinished()
    assert(rootPool.getSchedulableByName("1").runningTasks === 3)
    taskSetManager24.abort()
    assert(rootPool.getSchedulableByName("2").runningTasks === 2)
  }

  test("Nested Pool Test") {
    val rootPool = new Pool("", SchedulingMode.FAIR, 0, 0)
    val pool0 = new Pool("0", SchedulingMode.FAIR, 3, 1)
    val pool1 = new Pool("1", SchedulingMode.FAIR, 4, 1)
    rootPool.addSchedulable(pool0)
    rootPool.addSchedulable(pool1)

    val pool00 = new Pool("00", SchedulingMode.FAIR, 2, 2)
    val pool01 = new Pool("01", SchedulingMode.FAIR, 1, 1)
    pool0.addSchedulable(pool00)
    pool0.addSchedulable(pool01)

    val pool10 = new Pool("10", SchedulingMode.FAIR, 2, 2)
    val pool11 = new Pool("11", SchedulingMode.FAIR, 2, 1)
    pool1.addSchedulable(pool10)
    pool1.addSchedulable(pool11)

    val taskSetManager000 = createDummyTaskSetManager(0, 0, 5)
    val taskSetManager001 = createDummyTaskSetManager(0, 1, 5)
    pool00.addSchedulable(taskSetManager000)
    pool00.addSchedulable(taskSetManager001)

    val taskSetManager010 = createDummyTaskSetManager(1, 2, 5)
    val taskSetManager011 = createDummyTaskSetManager(1, 3, 5)
    pool01.addSchedulable(taskSetManager010)
    pool01.addSchedulable(taskSetManager011)

    val taskSetManager100 = createDummyTaskSetManager(2, 4, 5)
    val taskSetManager101 = createDummyTaskSetManager(2, 5, 5)
    pool10.addSchedulable(taskSetManager100)
    pool10.addSchedulable(taskSetManager101)

    val taskSetManager110 = createDummyTaskSetManager(3, 6, 5)
    val taskSetManager111 = createDummyTaskSetManager(3, 7, 5)
    pool11.addSchedulable(taskSetManager110)
    pool11.addSchedulable(taskSetManager111)

    checkTaskSetId(rootPool, 0)
    checkTaskSetId(rootPool, 4)
    checkTaskSetId(rootPool, 6)
    checkTaskSetId(rootPool, 2)
  }
}
