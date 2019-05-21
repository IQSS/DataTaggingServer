
import org.scalatestplus.play._
import edu.harvard.iq.datatags.model.graphs.Answer
import edu.harvard.iq.datatags.model.graphs.nodes.{AskNode, ToDoNode}
import models._

class InterviewSessionSpec extends PlaySpec {

  val mockModel = Model(null, "Mock", null, "", false, false, false, false)
  
"A New UserSession" must {
  "Have an empty traversed node history" in {
    val sut = InterviewSession.create(new VersionKit(None, null), mockModel, null)
    sut.traversed.size mustBe 0
    sut.answerHistory.size mustBe 0
  }
}

"A UserSession" must {
  "be updated when using updatedWith" in {
    val base = InterviewSession.create(new VersionKit(None, null), mockModel, null)
    val ans1 = AnswerRecord( new AskNode("a"), Answer.withName("indeed") )
    val history1 = Seq( new ToDoNode("a","a"), new ToDoNode("b","b"), new ToDoNode("c","c") )

    val updated = base.updatedWith( ans1, history1, null )

    updated.traversed mustEqual history1
    updated.answerHistory mustEqual Seq(ans1)
    
    val ans2 = AnswerRecord( new AskNode("b"), Answer.withName("indeed") )
    val history2 = Seq( new ToDoNode("B","B"), new ToDoNode("BB", "BB"), new ToDoNode("BBB", "BBB") )

    val updated2 = updated.updatedWith( ans2, history2, null )

    updated2.traversed mustEqual history1++history2
    updated2.answerHistory mustEqual Seq(ans1, ans2)
  }
}

}