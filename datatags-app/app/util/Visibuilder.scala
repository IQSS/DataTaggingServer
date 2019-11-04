package util

import edu.harvard.iq.datatags.model.slots.AbstractSlot
import edu.harvard.iq.datatags.model.values.{AbstractValue, AggregateValue, AtomicValue, CompoundValue, ToDoValue}
import play.api.Logger

import scala.collection.JavaConverters._

class Visibuilder(topSlots:Seq[String], topValues:Seq[String], typesPath:String) extends AbstractValue.Visitor[(Seq[AbstractValue], Seq[AbstractSlot])] {
  override def visitToDoValue(toDoValue: ToDoValue): (Seq[AbstractValue], Seq[AbstractSlot]) = {
    val pathSlot = if(typesPath == "") toDoValue.getSlot.getName else typesPath + "-" + toDoValue.getSlot.getName
    val pathValue = if(typesPath == "") toDoValue.getInfo else typesPath + "-" + toDoValue.getInfo
    (if(topValues.contains(pathValue)) Seq(toDoValue) else Seq(), if(topSlots.contains(pathSlot)) Seq(toDoValue.getSlot) else Seq())
  }

  override def visitAtomicValue(atomicValue: AtomicValue): (Seq[AbstractValue], Seq[AbstractSlot]) = {
    val pathSlot = if(typesPath == "") atomicValue.getSlot.getName else typesPath + "-" + atomicValue.getSlot.getName
    val pathValue = if(typesPath == "") atomicValue.getName else typesPath + "-" + atomicValue.getName
    (if(topValues.contains(pathValue)) Seq(atomicValue) else Seq(), if(topSlots.contains(pathSlot)) Seq(atomicValue.getSlot) else Seq())
  }

  override def visitAggregateValue(aggregateValue: AggregateValue): (Seq[AbstractValue], Seq[AbstractSlot]) = {
    val pathSlot = if(typesPath == "") aggregateValue.getSlot.getName else typesPath + "-" + aggregateValue.getSlot.getName
    (aggregateValue.getValues.asScala.filter(v => topValues.contains(typesPath + "-" + v.getName)).toSeq, if(topSlots.contains(pathSlot)) Seq(aggregateValue.getSlot) else Seq())
  }

  override def visitCompoundValue(compoundValue: CompoundValue): (Seq[AbstractValue], Seq[AbstractSlot]) = {
    val pathSlot = if(typesPath == "") compoundValue.getSlot.getName else typesPath + "-" + compoundValue.getSlot.getName
    compoundValue.getNonEmptySubSlots.asScala.map(slot => compoundValue.get(slot).accept(
      new Visibuilder(topSlots, topValues, if(typesPath == "") slot.getName else typesPath + "-" + slot.getName))).fold(
      (Seq(), //values
      if(topSlots.contains(pathSlot)) Seq(compoundValue.getSlot) else Seq()) //slots
    )((a, b) => {
//      Logger(classOf[Visibuilder]).info(typesPath)
//      Logger(classOf[Visibuilder]).info("val " + topValues.mkString(","))
//      Logger(classOf[Visibuilder]).info("sl " + topSlots.mkString(","))
//      Logger(classOf[Visibuilder]).info((a._1 ++ b._1).mkString(","))
//      Logger(classOf[Visibuilder]).info((a._2 ++ b._2).mkString(","))
      (a._1 ++ b._1, a._2 ++ b._2)
    })
  }
}
