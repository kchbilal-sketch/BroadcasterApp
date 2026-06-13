#!/bin/sh

JAVA_OPTS="-Xmx2048m"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec java $JAVA_OPTS -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
