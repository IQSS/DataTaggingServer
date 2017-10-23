package util

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

object FileUtils {
  
  def delete( path:Path ):Unit = {
    if ( Files.isDirectory(path) ) {
      Files.list(path).iterator().asScala.foreach( delete )
    }
    Files.delete(path)
  }
  
}
