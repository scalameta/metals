# Benchmarks

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
