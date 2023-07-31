# Gradle Configuration Cache Log To Speedscope

Space usage analysis for [Gradle configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html)
files via [speedscope](https://speedscope.app).

![speedscope screenshot](./speedscope.png)

## Installing

Build and install with Gradle:

    ./gradlew install


Setup an alias:

    alias gcc2ss=$(pwd)/build/install/gcc2speedscope/bin/gcc2speedscope

## Using

Produce a speedscope document from a Gradle debug log file:

    $ gcc2ss ~/my/debug.log >> speedscope.json

Produce a speedscope document from a Gradle build:

    $ ./gradlew assemble --configuration-cache -d | gcc2ss -- >> speedscope.json
