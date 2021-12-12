#!/bin/sh
echo ***** SETUP POSTGRES START  *****

PG_HBA_FILE=$(psql -c "show HBA_file" | grep conf)
PG_CONFIG_FILE=$(psql -c "show config_file" | grep conf)
echo PG_HBA: $PG_HBA_FILE
echo PG_CONF: $PG_CONFIG_FILE
echo "host  all  all  ::1/128       trust" >> $PG_HBA_FILE
echo "host  all  all  127.0.0.1/32  trust" >> $PG_HBA_FILE
echo "listen_addresses='*'" >> $PG_CONFIG_FILE

echo ***** SETUP POSTGRES DONE  *****
