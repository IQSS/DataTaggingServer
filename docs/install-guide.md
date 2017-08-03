# Install Guide

__note__:

This installation guide covers the installation of the Policy Models server only. Please refer to the [PolicyModels Library repository](https://github.com/IQSS/DataTaggingLibrary) for installation guide of the PolicyModels toolset.

PolicyModels server is a [Play!](https://www.playframework.com) application, with some external dependencies detailed below. We have tested it on Linux (CentOs 6 and 7), Mac OS and Windows. At the moment, all our production environments are base on CentOS.

## Prerequisites

### Software
* Java 1.8 or higher
* PostgreSQL 9.x
* Graphviz (available via a most package managers, and [here](http://graphviz.org))

### Filesystem
The server needs three folders it can write into and delete from:
* *models* - stores model data.
* *model-uploads* - temporary storage and staging area for uploaded models.
* *visualizations* - storage for visualization files.

### Accounts
* An email accounts to send notification emails from.

## Configuration

It is recommended to run publicly facing PolicyModels Servers behind a reverse proxy, such as Apache.

Below is a sample configuration file. Update field content as needed to fit your environment. *Do not change the `include` statement in the first line*.

```
include classpath("application.conf")
application.secret="add secret here"

# Database
slick.dbs.default.db.user="policy_models_server"
slick.dbs.default.db.password="policy_models_server_password"

# Graphviz
taggingServer.visualize.pathToDot="/usr/bin/dot"
 # taggingServer.visualize.style="-style=f11"

# Email
play.mailer {
  user     = "policymodels.server@host.com"
  password = "1234"
  host = "smtp.host.com"
  port =  465
  ssl = yes
  tls = no
  tlsRequired = no
  timeout = null
  connectiontimeout = null
  mock = no
}

# Folders
taggingServer.models.folder="path-to-models-folder"
taggingServer.visualize.folder="path-to-visualizations-folder"
taggingServer.model-uploads.folder="path-to-model-uploads-folder"
```

## Server Installation

PolicyModels server comes as an [SBT](http://www.scala-sbt.org) project. To create the files needed to install this software on a server, do the following:

1. Make sure you have SBT and all its requirements installed.
1. Clone or download the project to your computer.
1. Using a console, navigate to the project directory and type `sbt clean dist` (this might take a while)
1. At the end of the run, SBT will print the location of a generated .zip file with the of the project:
```
...
[info] Done packaging.
[info]
[info] Your package is ready in .../policymodelswebapp-1.0-SNAPSHOT.zip
```
1. Upload the zip file to the server
1. Unzip it. Let's assume the server was unzipped to `~/pm-server` directory.

## Server Startup

From `~/pm-server` directory:
```bash
./bin/policymodelswebapp -Dconfig.file=path-to-server.conf -J-Xmx1G&
```

### Notes
* If there are changes to the database schema that needs to be applied, add `-Dplay.evolutions.db.default.autoApply=true` to the command line.
* In case there are many models, the maximum memory might need to be increased. Change `-J-Xmx1G` to `-J-Xmx2G` or more.
