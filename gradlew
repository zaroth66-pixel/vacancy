#!/bin/sh
# Gradle start up script for UN*X
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME="`pwd -P`"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
DEFAULT_JVM_OPTS='-Xmx2048m -Xms512m'
die () { echo; echo "ERROR: $*"; echo; exit 1; } >&2
warn () { echo "$*"; } >&2
if [ -n "$JAVA_HOME" ] ; then
  if [ -x "$JAVA_HOME/jre/sh/java" ] ; then JAVACMD="$JAVA_HOME/jre/sh/java"
  else JAVACMD="$JAVA_HOME/bin/java"
  fi
  if [ ! -x "$JAVACMD" ] ; then die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
  fi
else JAVACMD="java"; which java > /dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' in PATH"
fi
CLASSPATH=$CLASSPATH
set -- \
  "-classpath" "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "$@"
