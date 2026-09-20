package scalaui.s6d

import javafx.application.Application
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.{Button, Label}
import javafx.scene.layout.VBox
import javafx.stage.Stage

/** The S6 reference app for Option D, matched to the Option A and B hellos: a label, a
  * button, and a counter.
  *
  * Written against the JavaFX API directly rather than ScalaFX. ScalaFX is a thin Scala
  * wrapper over exactly these classes, so it changes the source ergonomics but not the
  * thing S6 measures — the size and startup of a JavaFX app compiled by native-image.
  * Keeping the dependency list minimal also gives Substrate the best possible shot, so
  * the number is a floor for Option D rather than a handicapped one.
  */
class HelloD extends Application:
  override def start(stage: Stage): Unit =
    var count = 0
    val label = new Label("Count: 0")
    val button = new Button("Increment")
    button.setOnAction: _ =>
      count += 1
      label.setText(s"Count: $count")

    val root = new VBox(20, label, button)
    root.setAlignment(Pos.CENTER)
    stage.setScene(new Scene(root, 390, 700))
    stage.setTitle("Option D")
    stage.show()
    // Paired with the host timestamp the S6 harness takes before launch, the same way
    // Options A and B report.
    println(s"S6_FIRST_RENDER ${System.currentTimeMillis() / 1000.0}")

object HelloD:
  def main(args: Array[String]): Unit = Application.launch(classOf[HelloD], args*)
