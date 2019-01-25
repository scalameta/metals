# Benchmarks

## textDocument/definition

Date: 2018 October 8th, commit 59bda2ac81a497fa168677499bd1a9df60fec5ab
```
> bench/jmh:run -i 10 -wi 10 -f1 -t1
[info] Benchmark                   Mode  Cnt  Score   Error  Units
[info] MetalsBench.indexSources      ss   10  0.620 ± 0.058   s/op
[info] MetalsBench.javaMtags         ss   10  7.233 ± 0.017   s/op
[info] MetalsBench.scalaMtags        ss   10  4.617 ± 0.034   s/op
[info] MetalsBench.scalaToplevels    ss   10  0.361 ± 0.005   s/op
> bench/run
[info] info  elapsed: 3265ms
[info] info  java lines: 0
[info] info  scala lines: 1263569
[info] bench.Memory.printFootprint:11 iterable.source: "index"
[info] bench.Memory.printFootprint:12 Units.approx(size): "16.9M"
[info] bench.Memory.printFootprint:24 count: 12L
[info] bench.Memory.printFootprint:25 Units.approx(elementSize): "1.41M"
```


## workspace/symbol

```
[info] Benchmark                   (query)  Mode  Cnt    Score    Error  Units
[info] ClasspathFuzzBench.run  InputStream    ss   60   46.368 ±  3.053  ms/op
[info] ClasspathFuzzBench.run          Str    ss   60   65.793 ±  5.230  ms/op
[info] ClasspathFuzzBench.run         Like    ss   60   17.341 ±  0.211  ms/op
[info] ClasspathFuzzBench.run          M.E    ss   60  150.852 ± 15.329  ms/op
[info] ClasspathFuzzBench.run         File    ss   60   70.818 ±  4.629  ms/op
[info] ClasspathFuzzBench.run        Files    ss   60   49.294 ±  3.296  ms/op

[info] Benchmark                                          (query)  Mode  Cnt    Score   Error  Units
[info] WorkspaceFuzzBench.upper                               FSM    ss   30  215.994 ± 2.248  ms/op
[info] WorkspaceFuzzBench.upper                             Actor    ss   30  324.952 ± 1.523  ms/op
[info] WorkspaceFuzzBench.upper                            Actor(    ss   30   29.033 ± 0.942  ms/op
[info] WorkspaceFuzzBench.upper                             FSMFB    ss   30   15.477 ± 3.390  ms/op
[info] WorkspaceFuzzBench.upper                            ActRef    ss   30  186.815 ± 6.377  ms/op
[info] WorkspaceFuzzBench.upper                          actorref    ss   30  259.620 ± 1.112  ms/op
[info] WorkspaceFuzzBench.upper                         actorrefs    ss   30  148.240 ± 0.996  ms/op
[info] WorkspaceFuzzBench.upper                        fsmbuilder    ss   30  295.822 ± 7.603  ms/op
[info] WorkspaceFuzzBench.upper                fsmfunctionbuilder    ss   30  164.104 ± 2.003  ms/op
[info] WorkspaceFuzzBench.upper  abcdefghijklmnopqrstabcdefghijkl    ss   30  202.464 ± 1.423  ms/op
```

## Flamegraphs

Required steps before running.

```
git clone https://github.com/prisma/prisma.git
# (optional) open prisma/server directory with Metals, import build and compile everything
git clone https://github.com/brendangregg/FlameGraph
cd FlameGraph
git checkout 2bf846ebc632c4c1bea9ed31a4c452aa4f311fe0
cd ..
git clone https://github.com/chrishantha/jfr-flame-graph
cd jrf-flame-graph
./gradlew installDist
cd ..

export WORKSPACE=$(pwd)/prisma/server
export JFR_FLAME_GRAPH_DIR=$(pwd)/jfr-frame-graph
export FLAME_GRAPH_DIR=$(pwd)/FlameGraph
export PATH=$PATH:$JFR_FLAME_GRAPH_DIR/build/install/jfr-flame-graph/bin
```

Next, run JMH benchmark with the JFR profiler.

```
sbt
> bench/jmh:run -prof jmh.extras.JFR:dir=/tmp/profile-jfr2;flameGraphOpts=--minwidth,2;verbose=true -i 3 -wi 3 -f1 -t1 -p workspace=/path/to/workspace .*ServerInitializeBench
```

If all went well you should see an output like this in the end.
```
[info] Secondary result "bench.ServerInitializeBench.run:JFR":
[info] /tmp/profile-jfr2/profile.jfr
[info] /tmp/profile-jfr2/jfr-collapsed-cpu.txt
[info] /tmp/profile-jfr2/flame-graph-cpu.svg
[info] /tmp/profile-jfr2/flame-graph-cpu-reverse.svg
[info] /tmp/profile-jfr2/jfr-collapsed-cpu.txt
[info] /tmp/profile-jfr2/flame-graph-allocation-tlab.svg
[info] /tmp/profile-jfr2/flame-graph-allocation-tlab-reverse.svg
[info] Benchmark                                            (workspace)  Mode  Cnt   Score    Error  Units
[info] ServerInitializeBench.run      /Users/olafurpg/dev/prisma/server    ss    3  13.903 ± 43.178   s/op
```

Open the `*.svg` files in your browser to see the graphs.
