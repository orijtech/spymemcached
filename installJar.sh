#!/bin/sh

mvn install:install-file -Dfile=$(pwd)/target/spymemcached-3.000.000-SNAPSHOT.jar \
    -DgroupId=net.spy -DartifactId=spymemcached -Dversion=3.000.000 \
    -Dpackaging=jar -DgeneratePom=true
