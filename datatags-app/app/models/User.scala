package models

/**
  * A user in the system.
  */
case class User(
               username:String,
               name:String,
               email:String,
               orcid:String,
               url:String,
               encryptedPassword:String
               )
