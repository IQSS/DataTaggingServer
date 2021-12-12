# A docker file for creating a dev server for PolicyModels server.
# Source code is stored on the host computer, which accesses it from 
# aliased directory data/source
# Some parts adapted from https://docs.docker.com/samples/postgresql_service/
FROM debian
RUN apt update

# External Interfaces
## play
EXPOSE 9000/tcp
## PostgreSQL
EXPOSE 5432/tcp
RUN mkdir -p /etc/setup
#----------

# Install Required software
RUN apt-get install -y openjdk-11-jre
RUN apt-get install apt-transport-https curl gnupg -yqq
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" |  tee /etc/apt/sources.list.d/sbt.list
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian /" |  tee /etc/apt/sources.list.d/sbt_old.list
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --no-default-keyring --keyring gnupg-ring:/etc/apt/trusted.gpg.d/scalasbt-release.gpg --import
RUN chmod 644 /etc/apt/trusted.gpg.d/scalasbt-release.gpg
RUN apt update
RUN apt-get install -y scala
RUN apt-get install -y sbt
RUN apt-get install -y nodejs npm
RUN apt-get install -y graphviz
#----------

# Install and configure PostgreSQL
RUN apt-get install -y postgresql

# Run the rest of the commands as the ``postgres`` user created by the ``postgres-9.3`` package when it was ``apt-get installed``
USER postgres

# Create a PostgreSQL role named ``policymodels`` with ``policymodels`` as the password and
# then create a database `policymodels` owned by the ``policymodels`` role.
RUN /etc/init.d/postgresql start &&\
    psql --command "CREATE USER policy_models WITH SUPERUSER PASSWORD 'policy_models';" &&\
    createdb -O policy_models policy_models

# Adjust PostgreSQL configuration
USER root
COPY updated_pg_hba.conf /etc/postgresql/13/main/pg_hba.conf
RUN  chown postgres:postgres /etc/postgresql/13/main/pg_hba.conf

RUN mkdir -p /var/pomos
COPY server-start.sh /etc/setup/server-start.sh
RUN chmod 755 /etc/setup/server-start.sh
RUN useradd play
RUN mkdir /home/play
RUN chown play:play /home/play

RUN mkdir /var/coursier
RUN chmod 777 /var/coursier
ENV COURSIER_CACHE=/var/coursier

# Bridge the Mac and UNIX Grpahviz locations
RUN mkdir -p /usr/local/bin/
RUN chmod 777 /usr/local/bin/
RUN ln -s /usr/bin/dot /usr/local/bin/dot

# OK, run the play in dev mode
CMD /etc/setup/server-start.sh
