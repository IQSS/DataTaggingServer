package util

import edu.harvard.iq.datatags.model.values._
import edu.harvard.iq.datatags.model.values.TagValue
import models.CustomizationDTO
import play.api.libs.json._
import play.api.libs.json.Json

import scala.collection.JavaConversions._

// TODO merge with controllers.JSONFormats
object Jsonizer extends TagValue.Visitor[JsValue]{

	def visitToDoValue (todo: ToDoValue) = Json.toJson(todo.getType.getName)

	def visitAtomicValue (simple: AtomicValue) = Json.toJson(simple.getName)

	def visitAggregateValue (aggregate: AggregateValue) = Json.toJson(aggregate.getValues.map(visitAtomicValue))

	def visitCompoundValue (compound: CompoundValue) = {
		var compoundMap = collection.mutable.Map[String, JsValue]()
		for (fieldType <- compound.getNonEmptySubSlotTypes) {
			compoundMap += (fieldType.getName -> compound.get(fieldType).accept(this))
		}
		val compoundSeq = compoundMap.toSeq
		JsObject(compoundSeq)
	}
 
}
