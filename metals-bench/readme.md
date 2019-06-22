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
[info] ClasspathFuzzBench.run  InputStream    ss   30   51.488 ±  5.863  ms/op
[info] ClasspathFuzzBench.run          Str    ss   30   69.864 ±  7.779  ms/op
[info] ClasspathFuzzBench.run         Like    ss   30   20.670 ±  1.762  ms/op
[info] ClasspathFuzzBench.run          M.E    ss   30  111.428 ± 14.784  ms/op
[info] ClasspathFuzzBench.run         File    ss   30   82.478 ± 12.599  ms/op
[info] ClasspathFuzzBench.run        Files    ss   30   56.654 ±  6.465  ms/op

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

## textDocument/completions

First results show that loading a global instance of every request is too expensive.

```
[info] Benchmark                                        (completion)  Mode  Cnt   Score    Error  Units
[info] CachedSearchAndCompilerCompletionBench.complete     scopeOpen    ss   60  31.864 ± 16.848  ms/op
[info] CachedSearchAndCompilerCompletionBench.complete     scopeDeep    ss   60  68.081 ±  7.143  ms/op
[info] CachedSearchAndCompilerCompletionBench.complete    memberDeep    ss   60  45.872 ± 33.381  ms/op
```

The high error margins are caused by individual outliers

```
[info] Result "bench.CachedSearchAndCompilerCompletionBench.complete":
[info]   N = 60
[info]   mean =     45.872 ±(99.9%) 33.381 ms/op
[info]   Histogram, ms/op:
[info]     [  0.000,  50.000) = 59
[info]     [ 50.000, 100.000) = 0
[info]     [100.000, 150.000) = 0
[info]     [150.000, 200.000) = 0
[info]     [200.000, 250.000) = 0
[info]     [250.000, 300.000) = 0
[info]     [300.000, 350.000) = 0
[info]     [350.000, 400.000) = 0
[info]     [400.000, 450.000) = 0
[info]     [450.000, 500.000) = 0
[info]     [500.000, 550.000) = 0
[info]     [550.000, 600.000) = 0
[info]     [600.000, 650.000) = 1
[info]   Percentiles, ms/op:
[info]       p(0.0000) =     24.897 ms/op
[info]      p(50.0000) =     36.936 ms/op
[info]      p(90.0000) =     42.652 ms/op
[info]      p(95.0000) =     45.902 ms/op
[info]      p(99.0000) =    613.205 ms/op
[info]      p(99.9000) =    613.205 ms/op
[info]      p(99.9900) =    613.205 ms/op
[info]      p(99.9990) =    613.205 ms/op
[info]      p(99.9999) =    613.205 ms/op
[info]     p(100.0000) =    613.205 ms/op
[info] # Run complete. Total time: 00:00:47
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

## Classpath indexing

```
> bench/jmh:run -i 10 -wi 10 -f1 -t1 .*ClasspathIndexingBench
[info] Benchmark                   Mode  Cnt    Score    Error  Units
[info] ClasspathIndexingBench.run    ss   10  919.237  ± 42.827  ms/op # JDK 8
[info] ClasspathIndexingBench.run    ss   10  1316.451 ± 22.595  ms/op # JDK 11
```
