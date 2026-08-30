#!/usr/bin/env bash
cd /c/Users/tomik/vibe/minecraft/tower_defense
export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
exec ./.tools/gradle/gradle-9.1.0/bin/gradle "$@"
