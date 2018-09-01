# SamplePoMoSClientApp

This is a very simple web application that can request an interview from a PolicyModelsServer, and present the user with the result. This application is intended as a reference for people who need to write such clients.

# How to run this

You'll need:

* [SBT](https://www.scala-sbt.org/index.html)
* A running [PolicyModels Server](https://github.com/IQSS/DataTaggingServer/) running somewhere it can post back to this app (easy option: on the same computer).
* The code here, downloaded to your computer.
* Run this application:
  1. Using a terminal, navigate to the `samplepomosclientapp` folder in the downloaded repo.
  1. Type `sbt`. SBT loads the project and may initialize some data, this may take a while.
  2. Type `run -Dhttp.port=9001`. This runs the application locally, on port 9001.
  3. Navigate to http://localhost:9001 using your browser.
