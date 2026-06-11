#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build.sh  — compile all Java sources and produce ride-dispatch.jar
#
# Requirements: JDK 21+, libpostgresql-jdbc-java, libjackson2-databind-java
#   Ubuntu/Debian:
#     sudo apt-get install -y openjdk-21-jdk libpostgresql-jdbc-java \
#                             libjackson2-databind-java libjackson2-core-java
# ─────────────────────────────────────────────────────────────────────────────
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# ── Locate jars ───────────────────────────────────────────────────────────────
PG_JAR=$(find /usr/share/java -name "postgresql-*.jar" | sort | tail -1)
JD_JAR=$(find /usr/share/java -name "jackson-databind-*.jar" | sort | tail -1)
JC_JAR=$(find /usr/share/java -name "jackson-core-*.jar" | sort | tail -1)
JA_JAR=$(find /usr/share/java -name "jackson-annotations-*.jar" | sort | tail -1)
SL_JAR=$(find /usr/share/java -name "slf4j-simple-*.jar" | sort | tail -1)
SA_JAR=$(find /usr/share/java -name "slf4j-api-*.jar" | sort | tail -1)

for jar in "$PG_JAR" "$JD_JAR" "$JC_JAR" "$JA_JAR"; do
  if [ -z "$jar" ]; then
    echo "ERROR: Required jar not found. Install: libpostgresql-jdbc-java libjackson2-databind-java"
    exit 1
  fi
done

CP="$PG_JAR:$JD_JAR:$JC_JAR:$JA_JAR:${SL_JAR:-}:${SA_JAR:-}"

echo "Compiling..."
rm -rf out
mkdir -p out
javac --release 21 -cp "$CP" -d out src/com/ridedispatch/*.java
echo "Compiled OK"

echo "Packaging fat jar..."
cd out
for jar in "$PG_JAR" "$JD_JAR" "$JC_JAR" "$JA_JAR" "$SL_JAR" "$SA_JAR"; do
  [ -n "$jar" ] && jar xf "$jar"
done
rm -rf META-INF
mkdir -p META-INF
printf 'Manifest-Version: 1.0\nMain-Class: com.ridedispatch.Main\n\n' > META-INF/MANIFEST.MF
cd "$DIR"
jar cfm ride-dispatch.jar out/META-INF/MANIFEST.MF -C out .
echo "Built: ride-dispatch.jar ($(du -sh ride-dispatch.jar | cut -f1))"
