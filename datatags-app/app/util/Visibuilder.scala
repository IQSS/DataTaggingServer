package util

import edu.harvard.iq.policymodels.model.policyspace.values.{AbstractValue, AggregateValue, AtomicValue, CompoundValue, ToDoValue}


import scala.collection.JavaConverters._

case class ReportTopVisibility(topValues:Seq[AbstractValue],
                               topSlots:Seq[(String, AbstractValue)])

class Visibuilder(topSlots:Seq[String], topValues:Seq[String], typesPath:String) extends AbstractValue.Visitor[ReportTopVisibility] {
  override def visitToDoValue(toDoValue: ToDoValue): ReportTopVisibility = {
    val pathSlot = if(typesPath == "") toDoValue.getSlot.getName else typesPath + "-" + toDoValue.getSlot.getName
    val pathValue = pathSlot + "-" + toDoValue.getInfo
    ReportTopVisibility(if(topValues.contains(pathValue)) Seq(toDoValue) else Seq(), if(topSlots.contains(pathSlot)) Seq((typesPath, toDoValue)) else Seq())
  }

  override def visitAtomicValue(atomicValue: AtomicValue): ReportTopVisibility = {
    val pathSlot = if(typesPath == "") atomicValue.getSlot.getName else typesPath + "-" + atomicValue.getSlot.getName
    val pathValue = pathSlot + "-" + atomicValue.getName
    ReportTopVisibility(if(topValues.contains(pathValue)) Seq(atomicValue) else Seq(), if(topSlots.contains(pathSlot)) Seq((typesPath, atomicValue)) else Seq())
  }

  override def visitAggregateValue(aggregateValue: AggregateValue): ReportTopVisibility = {
    val pathSlot = if(typesPath == "") aggregateValue.getSlot.getName else typesPath + "-" + aggregateValue.getSlot.getName
    ReportTopVisibility(aggregateValue.getValues.asScala.filter(v => topValues.contains(pathSlot + "-" + v.getName)).toSeq,
      if(topSlots.contains(pathSlot)) Seq((typesPath, aggregateValue)) else Seq())
  }

  override def visitCompoundValue(compoundValue: CompoundValue): ReportTopVisibility = {
    val pathSlot = if(typesPath == "") compoundValue.getSlot.getName else typesPath + "-" + compoundValue.getSlot.getName
    compoundValue.getNonEmptySubSlots.asScala.map(slot => compoundValue.get(slot).accept(
      new Visibuilder(topSlots, topValues, pathSlot))).fold(
      ReportTopVisibility(Seq(), //values
      if(topSlots.contains(pathSlot)) Seq((typesPath,compoundValue)) else Seq()) //slots
    )((a, b) => ReportTopVisibility(a.topValues ++ b.topValues, a.topSlots ++ b.topSlots))
  }
}

