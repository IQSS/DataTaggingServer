package util

import edu.harvard.iq.datatags.model.values._
import edu.harvard.iq.datatags.model.values.AbstractValue
import play.api.libs.json._
import play.api.libs.json.Json

import scala.collection.JavaConversions._

// TODO merge with controllers.JSONFormats
object Jsonizer extends AbstractValue.Visitor[JsValue]{

	def visitToDoValue (todo: ToDoValue) = Json.toJson(todo.getSlot.getName)

	def visitAtomicValue (simple: AtomicValue) = Json.toJson(simple.getName)

	def visitAggregateValue (aggregate: AggregateValue) = Json.toJson(aggregate.getValues.map(visitAtomicValue))

	def visitCompoundValue (compound: CompoundValue) = {
		var compoundMap = collection.mutable.Map[String, JsValue]()
		for (fieldType <- compound.getNonEmptySubSlots) {
			compoundMap += (fieldType.getName -> compound.get(fieldType).accept(this))
		}
		val compoundSeq = compoundMap.toSeq
		JsObject(compoundSeq)
	}
 
}
