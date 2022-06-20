echo PolicyModelsServer dev env
service postgresql start
/bin/bash

#clone the server repo:
cd /var/pomos
git clone https://github.com/IQSS/DataTaggingServer.git
cd /var/pomos/DataTaggingServer/datatags-app

#adding preconditions folders:
mkdir -p var/models
mkdir -p var/model-uploads

#run the server in play mode
sbt -Dplay.evolutions.db.default.autoApply=true -Dsbt.coursier.home="/var/coursier" run &

#maybe it give it more sleep time depending on the server startup time
sleep 240 &&

#adding admin locally from the docker
curl -X POST -d '{"username": "admin", "password":"pass"}' http://localhost:9000/admin/api/users/
