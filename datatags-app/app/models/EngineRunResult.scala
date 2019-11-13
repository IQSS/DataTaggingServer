package models

import edu.harvard.iq.policymodels.runtime.RuntimeEngineState
import edu.harvard.iq.policymodels.model.decisiongraph.nodes._
import edu.harvard.iq.policymodels.runtime.exceptions.DataTagsRuntimeException


case class EngineRunResult( state: RuntimeEngineState,
                        traversed: Seq[Node],
                            error: DataTagsRuntimeException
) 