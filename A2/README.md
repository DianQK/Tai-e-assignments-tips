# A2

## 以下内容结合食用

在此次作业中，你需要实现针对 int 类型的常量传播。注意，在 Java 中，boolean、byte、char 和 short 类型在运行时实际上都以 int 值的形式进行表示和计算，因此你的分析算法也应当能处理这些类型。**其他基本数据类型（例如 long、float 和 double）以及引用类型（例如 class types、array types）不在此次作业的考虑范围内，所以你可以在分析时忽略它们的值。**

你已经在作业 1 中见到过这几个 API，他们是从 DataflowAnalysis 中继承下来的，需要注意的是：在实现 newBoundaryFact() 的时候，你要小心地处理每个会被分析的方法的参数。**具体来说，你要将它们的值初始化为 NAC (请思考：为什么要这么做？)。**

感谢：https://github.com/MirageLyu/Tai-e-assignments/commit/a1b827267773a3f0b40875a01a37f3d75608c2f2#diff-0de033441703a7c75d3092bebfa08ac64ee9e7a8b88e66d7e62872f7ece75677R59


## 关注 Issues

https://github.com/pascal-lab/Tai-e-assignments/issues/2
