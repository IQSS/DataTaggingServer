package models

import java.sql.Timestamp
import java.util.UUID

case class InterviewHistory(key:UUID,
                            modelId:String,
                            versionNum:Int,
                            loc:String,
                            path:String,
                            agent:String)

case class InterviewHistoryRecord(ihKey:UUID,
                                  time:Timestamp,
                                  action:String)
