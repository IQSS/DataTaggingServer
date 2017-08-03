package models

import java.sql.Timestamp

/**
  * Created by mor_vilozni on 02/08/2017.
  */
case class UuidForForgotPassword (
                                 username:String,
                                 uuid:String,
                                 resetPasswordDate:Timestamp
                                 )
