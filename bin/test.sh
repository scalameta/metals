#!/bin/bash

function bloop_version {
  grep "val bloop =" project/V.scala | sed -n 's/.*"\(.*\)".*/\1/p'
}

function run_with_restart_loop {
  local max_attempts=5
  local timeout_seconds=20
  local attempt=1
  
  while [ $attempt -le $max_attempts ]; do
    echo "Attempt $attempt/$max_attempts: Running bloop command..."
    
    # Run the command with timeout
    timeout $timeout_seconds ./coursier launch -M bloop.cli.Bloop -r sonatype:snapshots ch.epfl.scala:bloop-cli_2.13:$(bloop_version) -- about
    local exit_code=$?
    
    if [ $exit_code -eq 0 ]; then
      echo "Bloop command completed successfully"
      return 0
    elif [ $exit_code -eq 124 ]; then
      echo "Bloop command timed out after ${timeout_seconds} seconds (attempt $attempt/$max_attempts)"
      # Kill any remaining coursier/bloop processes
      pkill -f "coursier.*bloop" || true
      pkill -f "bloop" || true
      sleep 2
    else
      echo "Bloop command failed with exit code $exit_code (attempt $attempt/$max_attempts)"
      sleep 2
    fi
    
    attempt=$((attempt + 1))
  done
  
  echo "All $max_attempts attempts failed. Continuing with script..."
  return 1
}

export COURSIER_REPOSITORIES="central|sonatype:snapshots"
export BLOOP_JAVA_OPTS="-Xss4m -Xmx2G -XX:MaxInlineLevel=20 -XX:+UseZGC -XX:ZUncommitDelay=30 -XX:ZCollectionInterval=5 -XX:+IgnoreUnrecognizedVMOptions -Dbloop.ignore-sig-int=true"

mkdir -p ~/.bloop
curl -Lo coursier https://git.io/coursier-cli && chmod +x coursier
run_with_restart_loop

cat ~/.local/share/scalacli/bloop/daemon/output

echo $PWD
sbt "$1"

# sbt must be the last command - its exit code signals if tests passed or not
