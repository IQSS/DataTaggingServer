DataTags Tagging Server
============

A web application running DataTags interviews. This system helps dataset owners in creating a dataset handling policy that is both legal, and informed by current technologies.

This server library uses the core [DataTags language library](https://github.com/IQSS/DataTaggingLibrary).

For more information, visit [datatags.org](http://datatags.org).

## Initial set data

* configuration: see conf/application.conf
* add user via API from localhost (useful for first user setup):

    `echo \{\"username\": \"admin\", \"password\":\"pass\"\} | http POST localhost:9000/admin/api/users/`
