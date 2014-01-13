name := "orchard-javafx"

scalaVersion := "2.10.2"

unmanagedJars in Compile := {
  val base = baseDirectory.value
  val customJars = (base ** "*.jar") +++ (file("/opt/java/jre/lib/jfxrt.jar"))
  customJars.classpath
}

mainClass := Some("orchard.ui.javafx.Editor")

exportJars := true

scalacOptions += "-feature"

fork := true
